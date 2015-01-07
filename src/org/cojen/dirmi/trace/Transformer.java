/*
 *  Copyright 2006-2011 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.trace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;

import java.security.ProtectionDomain;

import java.util.HashMap;
import java.util.Map;

import org.cojen.classfile.attribute.Annotation;

import org.cojen.classfile.ClassFile;
import org.cojen.classfile.CodeAssembler;
import org.cojen.classfile.CodeBuilder;
import org.cojen.classfile.CodeDisassembler;
import org.cojen.classfile.DelegatedCodeAssembler;
import org.cojen.classfile.Label;
import org.cojen.classfile.LocalVariable;
import org.cojen.classfile.MethodInfo;
import org.cojen.classfile.Modifiers;
import org.cojen.classfile.Opcode;
import org.cojen.classfile.TypeDesc;

import org.cojen.classfile.constant.ConstantIntegerInfo;
import org.cojen.classfile.constant.ConstantStringInfo;
import org.cojen.classfile.constant.ConstantUTFInfo;

import org.cojen.dirmi.Trace;

import static org.cojen.dirmi.trace.TraceMode.*;
import static org.cojen.dirmi.trace.TraceModes.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class Transformer implements ClassFileTransformer {
    private static final boolean DEBUG;
    private static final String HANDLER_FIELD_PREFIX;

    static {
        DEBUG = Boolean.getBoolean("org.cojen.dirmi.trace.Transformer.DEBUG");
        HANDLER_FIELD_PREFIX = Transformer.class.getName().replace('.', '$') + "$handler$";
    }

    private static int cHandlerFieldCounter = 0;

    private static synchronized String handlerFieldName() {
        String name = HANDLER_FIELD_PREFIX + cHandlerFieldCounter;
        cHandlerFieldCounter++;
        return name;
    }

    private final TraceAgent mAgent;
    final String mHandlerFieldName;

    Transformer(TraceAgent agent) {
        mAgent = agent;
        mHandlerFieldName = handlerFieldName();
    }

    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer)
        throws IllegalClassFormatException
    {
        if (loader == null) {
            // Cannot transform classes loaded by bootstrap loader.
            return null;
        }

        // Test if loader can access TraceAgent.
        try {
            loader.loadClass(TraceAgent.class.getName());
        } catch (ClassNotFoundException e) {
            return null;
        }

        className = className.replace('/', '.');

        // Handle special cases...
        if (className.startsWith("org.cojen.dirmi.trace.") ||
            className.startsWith("sun.reflect."))
        {
            return null;
        }

        TraceModes modes = mAgent.getTraceModes(className);

        if (modes == null || modes == ALL_OFF) {
            return null;
        }

        ClassFile cf;
        try {
            cf = ClassFile.readFrom(new ByteArrayInputStream(classfileBuffer));
        } catch (Exception e) {
            IllegalClassFormatException e2 = new IllegalClassFormatException();
            e2.initCause(e);
            throw e2;
        }

        if (cf.getModifiers().isInterface()) {
            // Short-circuit. Nothing to transform.
            return null;
        }

        Map<MethodInfo, MidAndOp> transformedMethods = new HashMap<MethodInfo, MidAndOp>();

        for (MethodInfo mi : cf.getMethods()) {
            tryTransform(modes, transformedMethods, mi);
        }

        for (MethodInfo mi : cf.getConstructors()) {
            tryTransform(modes, transformedMethods, mi);
        }

        if (transformedMethods.size() == 0) {
            // Class is unchanged.
            return null;
        }

        // Add field for holding reference to handler.
        cf.addField(Modifiers.PRIVATE.toStatic(true),
                    mHandlerFieldName, TypeDesc.forClass(TraceHandler.class));
        
        // Add or prepend static initializer for getting handler and
        // registering methods.
        {
            MethodInfo clinit = cf.getInitializer();
            CodeDisassembler dis;
            if (clinit == null) {
                dis = null;
                clinit = cf.addInitializer();
            } else {
                dis = new CodeDisassembler(clinit);
            }

            CodeBuilder b = new CodeBuilder(clinit);

            TypeDesc agentType = TypeDesc.forClass(TraceAgent.class);
            LocalVariable agentVar = b.createLocalVariable("agent", agentType);
            b.loadConstant(mAgent.getAgentId());
            b.invokeStatic(agentType, "getTraceAgent",
                           agentType, new TypeDesc[] {TypeDesc.LONG});
            b.storeLocal(agentVar);

            TypeDesc handlerType = TypeDesc.forClass(TraceHandler.class);
            b.loadLocal(agentVar);
            b.invokeVirtual(agentType, "getTraceHandler", handlerType, null);
            b.storeStaticField(mHandlerFieldName, handlerType);

            // Finish registering each method.
            TypeDesc classType = TypeDesc.forClass(Class.class);
            TypeDesc classArrayType = classType.toArrayType();

            for (Map.Entry<MethodInfo, MidAndOp> entry : transformedMethods.entrySet()) {
                MethodInfo mi = entry.getKey();
                MidAndOp midAndOp = entry.getValue();

                // For use below when calling registerTraceMethod.
                b.loadLocal(agentVar);

                b.loadConstant(midAndOp.mid);
                b.loadConstant(midAndOp.operation);

                b.loadConstant(cf.getType());
                b.loadConstant(mi.getName().equals("<init>") ? null : mi.getName());

                TypeDesc returnType = mi.getMethodDescriptor().getReturnType();
                if (returnType == null || returnType == TypeDesc.VOID) {
                    b.loadNull();
                } else {
                    b.loadConstant(returnType);
                }

                int hasThis = mi.getModifiers().isStatic() ? 0 : 1;

                TypeDesc[] types = mi.getMethodDescriptor().getParameterTypes();
                if (hasThis + types.length == 0) {
                    b.loadNull();
                } else {
                    b.loadConstant(hasThis + types.length);
                    b.newObject(classArrayType);
                    for (int i=0; i<types.length; i++) {
                        // dup array
                        b.dup();
                        b.loadConstant(hasThis + i);
                        b.loadConstant(types[i]);
                        b.storeToArray(classType);
                    }
                }

                b.invokeVirtual(agentType, "registerTraceMethod", null, new TypeDesc[]
                    {TypeDesc.INT, TypeDesc.STRING,
                     classType, TypeDesc.STRING, classType, classArrayType});
            }

            if (dis == null) {
                b.returnVoid();
            } else {
                dis.disassemble(b);
            }
        }

        // Define the newly transformed class.

        if (DEBUG) {
            File file = new File(cf.getClassName().replace('.', '/') + ".class");
            try {
                File tempDir = new File(System.getProperty("java.io.tmpdir"));
                file = new File(tempDir, file.getPath());
            } catch (SecurityException e) {
            }
            try {
                file.getParentFile().mkdirs();
                System.out.println("Dirmi trace Transformer writing to " + file);
                OutputStream out = new FileOutputStream(file);
                cf.writeTo(out);
                out.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            cf.writeTo(out);
            out.close();
        } catch (Exception e) {
            IllegalClassFormatException e2 = new IllegalClassFormatException();
            e2.initCause(e);
            throw e2;
        }

        return out.toByteArray();
    }

    private String getStringParam(Map<String, Annotation.MemberValue> memberValues,
                                  String paramName)
    {
        Annotation.MemberValue mv = memberValues.get(paramName);
        Object constant;
        if (mv != null && (constant = mv.getValue()) != null) {
            if (constant instanceof ConstantUTFInfo) {
                return ((ConstantUTFInfo) constant).getValue();
            }
            if (constant instanceof ConstantStringInfo) {
                return ((ConstantStringInfo) constant).getValue();
            }
        }
        return null;
    }

    private Boolean getBooleanParam(Map<String, Annotation.MemberValue> memberValues,
                                    String paramName)
    {
        Annotation.MemberValue mv = memberValues.get(paramName);
        Object constant;
        if (mv != null && (constant = mv.getValue()) != null
            && constant instanceof ConstantIntegerInfo) {
            return ((ConstantIntegerInfo) constant).getValue() != 0;
        }
        return null;
    }

    private boolean getBooleanParam(Map<String, Annotation.MemberValue> memberValues,
                                    String paramName,
                                    boolean defaultValue)
    {
        Boolean value = getBooleanParam(memberValues, paramName);
        return value == null ? defaultValue : value;
    }

    private void tryTransform(TraceModes modes,
                              Map<MethodInfo, MidAndOp> transformedMethods,
                              MethodInfo mi)
    {
        if (mi.getModifiers().isAbstract() || mi.getModifiers().isNative()) {
            return;
        }

        Annotation traceAnnotation = null;
        findTrace: {
            Annotation[] annotations = mi.getRuntimeVisibleAnnotations();
            for (Annotation ann : annotations) {
                if (ann.getType().getFullName().equals(Trace.class.getName())) {
                    traceAnnotation = ann;
                    break findTrace;
                }
            }
        }

        String operation;
        boolean args, result, exception, time, root, graft;

        if (traceAnnotation == null) {
            // If no user trace annotation exists, check if any trace mode is
            // on which will turn on trace anyhow.

            operation = null;
            args = modes.getTraceArguments() == ON;
            result = modes.getTraceResult() == ON;
            exception = modes.getTraceException() == ON;
            time = modes.getTraceTime() == ON;

            if (!args && !result && !exception && !time) {
                if (modes.getTraceCalls() != ON) {
                    // No features forced on, so don't trace.
                    return;
                }
            }

            root = false;
            graft = false;
        } else {
            // Extract trace parameters. Defaults copied from Trace annotation.

            Map<String, Annotation.MemberValue> memberValues = traceAnnotation.getMemberValues();

            operation = getStringParam(memberValues, "operation");
            if ("".equals(operation)) {
                operation = null;
            }

            args = getBooleanParam(memberValues, "args", false);
            if (modes.getTraceArguments() != USER) {
                args = modes.getTraceArguments() == ON;
            }

            result = getBooleanParam(memberValues, "result", false);
            if (modes.getTraceResult() != USER) {
                result = modes.getTraceResult() == ON;
            }

            exception = getBooleanParam(memberValues, "exception", false);
            if (modes.getTraceException() != USER) {
                exception = modes.getTraceException() == ON;
            }

            time = getBooleanParam(memberValues, "time", true);
            if (modes.getTraceTime() != USER) {
                time = modes.getTraceTime() == ON;
            }

            if (!args && !result && !exception && !time) {
                if (modes.getTraceCalls() == OFF) {
                    // No features on, so don't trace.
                    return;
                }
            }

            root = getBooleanParam(memberValues, "root", false);
            graft = getBooleanParam(memberValues, "graft", false);
        }

        int mid = transform(mi, operation, args, result, exception, time, root, graft);

        transformedMethods.put(mi, new MidAndOp(mid, operation));
    }

    /**
     * @param operation optional trace operation name
     * @param args when true, pass method arguments to trace handler
     * @param result when true, pass method return value to trace handler
     * @param exception when true, pass thrown exception to trace handler
     * @param time when true, pass method execution time to trace handler
     * @param root when true, indicate to trace handler that method should be reported as root
     * @param graft when true, indicate to trace handler that method should be reported as graft
     * @return method id
     */
    private int transform(MethodInfo mi,
                          String operation,
                          boolean args,
                          boolean result,
                          boolean exception,
                          boolean time,
                          boolean root,
                          boolean graft)
    {
        if (mi.getMethodDescriptor().getReturnType() == TypeDesc.VOID) {
            result = false;
        }

        int mid = mAgent.reserveMethod(root, graft);

        CodeDisassembler dis = new CodeDisassembler(mi);
        CodeBuilder b = new CodeBuilder(mi);

        // Call enterMethod
        {
            b.loadStaticField(mHandlerFieldName, TypeDesc.forClass(TraceHandler.class));

            b.loadConstant(mid);

            TypeDesc[] params;
            if (!args) {
                params = new TypeDesc[] {TypeDesc.INT};
            } else {
                int argCount = mi.getMethodDescriptor().getParameterCount();
                int hasThis = mi.getModifiers().isStatic() ? 0 : 1;
                argCount += hasThis;

                if (argCount == 0) {
                    params = new TypeDesc[] {TypeDesc.INT};
                } else if (argCount == 1) {
                    params = new TypeDesc[] {TypeDesc.INT, TypeDesc.OBJECT};
                    if (hasThis != 0) {
                        b.loadThis();
                    } else {
                        b.loadLocal(b.getParameter(0));
                        b.convert(b.getParameter(0).getType(), TypeDesc.OBJECT);
                    }
                } else {
                    params = new TypeDesc[] {TypeDesc.INT, TypeDesc.OBJECT.toArrayType()};
                    b.loadConstant(argCount);
                    b.newObject(TypeDesc.OBJECT.toArrayType());

                    if (hasThis != 0) {
                        // dup array
                        b.dup();
                        b.loadConstant(0);
                        b.loadThis();
                        b.storeToArray(TypeDesc.OBJECT);
                        argCount--;
                    }

                    for (int i=0; i<argCount; i++) {
                        // dup array
                        b.dup();
                        b.loadConstant(hasThis + i);
                        b.loadLocal(b.getParameter(i));
                        b.convert(b.getParameter(i).getType(), TypeDesc.OBJECT);
                        b.storeToArray(TypeDesc.OBJECT);
                    }
                }
            }

            b.invokeInterface(TypeDesc.forClass(TraceHandler.class), "enterMethod", null, params);
        }

        LocalVariable startTime = null;
        if (time) {
            startTime = b.createLocalVariable("startTime", TypeDesc.LONG);
            b.invokeStatic(TypeDesc.forClass(System.class), "nanoTime", TypeDesc.LONG, null);
            b.storeLocal(startTime);
        }

        Label tryStart = b.createLabel().setLocation();
        Label tryEnd = b.createLabel();

        dis.disassemble(b, null, tryEnd);

        tryEnd.setLocation();

        // Fall to this point for normal exit.
        {
            // Save result in local variable to pass to exitMethod (if result passing enabled)
            LocalVariable resultVar = null;
            if (result) {
                resultVar = b.createLocalVariable
                    ("result", mi.getMethodDescriptor().getReturnType());
                b.storeLocal(resultVar);
            }
            
            // Prepare call to exit method
            b.loadStaticField(mHandlerFieldName, TypeDesc.forClass(TraceHandler.class));
            b.loadConstant(mid);
            
            TypeDesc[] exitMethodParams;
            if (time) {
                if (result) {
                    exitMethodParams = new TypeDesc[] {
                        TypeDesc.INT, TypeDesc.OBJECT, TypeDesc.LONG
                    };
                    b.loadLocal(resultVar);
                    b.convert(resultVar.getType(), TypeDesc.OBJECT);
                } else {
                    exitMethodParams = new TypeDesc[] {TypeDesc.INT, TypeDesc.LONG};
                }
            } else if (result) {
                exitMethodParams = new TypeDesc[] {TypeDesc.INT, TypeDesc.OBJECT};
                b.loadLocal(resultVar);
                b.convert(resultVar.getType(), TypeDesc.OBJECT);
            } else {
                exitMethodParams = new TypeDesc[] {TypeDesc.INT};
            }
            
            if (time) {
                b.invokeStatic(TypeDesc.forClass(System.class), "nanoTime", TypeDesc.LONG, null);
                b.loadLocal(startTime);
                b.math(Opcode.LSUB);
                // Leave on stack for exitMethod call.
            }
            
            // Call exitMethod
            b.invokeInterface(TypeDesc.forClass(TraceHandler.class), "exitMethod",
                              null, exitMethodParams);
            
            if (result) {
                b.loadLocal(resultVar);
            }
            b.returnValue(mi.getMethodDescriptor().getReturnType());
        }

        b.exceptionHandler(tryStart, tryEnd, null);

        // Fall to this point for exception exit.

        {
            // Save exception in local variable to pass to exitMethod (if
            // exception passing enabled)
            TypeDesc throwableType = TypeDesc.forClass(Throwable.class);
            LocalVariable exceptionVar = null;
            if (exception) {
                exceptionVar = b.createLocalVariable("e", throwableType);
                b.storeLocal(exceptionVar);
            }
            
            b.loadStaticField(mHandlerFieldName, TypeDesc.forClass(TraceHandler.class));
            b.loadConstant(mid);
            
            TypeDesc[] exitMethodParams;
            if (time) {
                if (exception) {
                    exitMethodParams = new TypeDesc[] {TypeDesc.INT, throwableType, TypeDesc.LONG};
                    b.loadLocal(exceptionVar);
                } else {
                    exitMethodParams = new TypeDesc[] {TypeDesc.INT, TypeDesc.LONG};
                }
            } else if (exception) {
                exitMethodParams = new TypeDesc[] {TypeDesc.INT, throwableType};
                b.loadLocal(exceptionVar);
            } else {
                exitMethodParams = new TypeDesc[] {TypeDesc.INT};
            }
            
            if (time) {
                b.invokeStatic(TypeDesc.forClass(System.class), "nanoTime", TypeDesc.LONG, null);
                b.loadLocal(startTime);
                b.math(Opcode.LSUB);
                // Leave on stack for exitMethod call.
            }
            
            // Call exitMethod
            b.invokeInterface(TypeDesc.forClass(TraceHandler.class), "exitMethod",
                              null, exitMethodParams);

            if (exception) {
                b.loadLocal(exceptionVar);
            }
            b.throwObject();
        }

        return mid;
    }

    private static class MidAndOp {
        public final int mid;
        public final String operation;

        MidAndOp(int mid, String operation) {
            this.mid = mid;
            this.operation = operation;
        }
    }
}
