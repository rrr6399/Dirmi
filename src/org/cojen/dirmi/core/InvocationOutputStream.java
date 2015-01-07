/*
 *  Copyright 2006-2010 Brian S O'Neill
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

package org.cojen.dirmi.core;

import java.io.IOException;
import java.io.ObjectOutput;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standard implementation of {@link InvocationOutput}.
 *
 * @author Brian S O'Neill
 * @see InvocationInputStream
 */
public class InvocationOutputStream extends OutputStream implements InvocationOutput {
    static final String SKELETON_GENERATOR_NAME;

    private static final boolean cPruneStackTraces;

    static {
        SKELETON_GENERATOR_NAME = SkeletonFactoryGenerator.class.getName();

        boolean prune;
        try {
            String prop = System.getProperty("org.cojen.dirmi.pruneServerStackTraces");
            prune = prop == null || prop.equalsIgnoreCase("true");
        } catch (SecurityException e) {
            prune = true;
        }
        cPruneStackTraces = prune;
    }

    static final byte FALSE = 0;
    static final byte TRUE = 1;
    static final byte NULL = 2;
    static final byte NOT_NULL = 3;

    private final InvocationChannel mChannel;
    private final DrainableObjectOutputStream mOut;

    /**
     * @param out stream to wrap
     */
    public InvocationOutputStream(InvocationChannel channel, DrainableObjectOutputStream out) {
        mChannel = channel;
        mOut = out;
    }

    public void write(int b) throws IOException {
        mOut.write(b);
    }

    public void write(byte[] b) throws IOException {
        mOut.write(b);
    }

    public void write(byte[] b, int offset, int length) throws IOException {
        mOut.write(b, offset, length);
    }

    public void writeBoolean(boolean v) throws IOException {
        mOut.writeBoolean(v);
    }

    public void writeByte(int v) throws IOException {
        mOut.writeByte(v);
    }

    public void writeShort(int v) throws IOException {
        mOut.writeShort(v);
    }

    public void writeChar(int v) throws IOException {
        mOut.writeChar(v);
    }

    public void writeInt(int v) throws IOException {
        mOut.writeInt(v);
    }

    public void writeLong(long v) throws IOException {
        mOut.writeLong(v);
    }

    public void writeFloat(float v) throws IOException {
        mOut.writeFloat(v);
    }

    public void writeDouble(double v) throws IOException {
        mOut.writeDouble(v);
    }

    public void writeBytes(String s) throws IOException {
        mOut.writeBytes(s);
    }

    public void writeChars(String s) throws IOException {
        mOut.writeChars(s);
    }

    public void writeUTF(String s) throws IOException {
        mOut.writeUTF(s);
    }

    /**
     * @param str string of any length or null
     */
    public void writeUnsharedString(String str) throws IOException {
        if (str == null) {
            mOut.write(0);
            return;
        }

        int length = str.length();

        // Add one in order to reserve zero for the null string.
        writeVarUnsignedInt(length + 1);

        // Strings are encoded in a fashion similar to UTF-8, in that ASCII
        // characters are written in one byte. This encoding is more efficient
        // than UTF-8, but it isn't compatible with UTF-8.
 
        OutputStream out = mOut;
        for (int i = 0; i < length; i++) {
            int c = str.charAt(i);
            if (c <= 0x7f) {
                out.write(c);
            } else if (c <= 0x3fff) {
                out.write(0x80 | (c >> 8));
                out.write(c);
            } else {
                if (c >= 0xd800 && c <= 0xdbff) {
                    // Found a high surrogate. Verify that surrogate pair is
                    // well-formed. Low surrogate must follow high surrogate.
                    if (i + 1 < length) {
                        int c2 = str.charAt(i + 1);
                        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                            c = 0x10000 + (((c & 0x3ff) << 10) | (c2 & 0x3ff));
                            i++;
                        }
                    }
                }
                out.write(0xc0 | (c >> 16));
                out.write(c >> 8);
                out.write(c);
            }
        }
    }

    private void writeVarUnsignedInt(int v) throws IOException {
        OutputStream out = mOut;
        if (v < (1 << 7)) {
            out.write(v);
        } else if (v < (1 << 14)) {
            out.write((v >> 8) | 0x80);
            out.write(v);
        } else if (v < (1 << 21)) {
            out.write((v >> 16) | 0xc0);
            out.write(v >> 8);
            out.write(v);
        } else if (v < (1 << 28)) {
            out.write((v >> 24) | 0xe0);
            out.write(v >> 16);
            out.write(v >> 8);
            out.write(v);
        } else {
            out.write(0xf0);
            out.write(v >> 24);
            out.write(v >> 16);
            out.write(v >> 8);
            out.write(v);
        }
    }

    public void writeUnshared(Object obj) throws IOException {
        mOut.writeUnshared(obj);
    }

    public void writeObject(Object obj) throws IOException {
        mOut.writeObject(obj);
    }

    public void writeThrowable(Throwable t) throws IOException {
        if (t == null) {
            write(NULL);
            return;
        }

        write(NOT_NULL);

        // Could just serialize Throwable, however:
        // 1. Caller might not have class for Throwable
        // 2. Throwable might not actually be serializable

        // So write as much as possible without having to serialize actual
        // Throwable, and then write serialized Throwable. If a
        // NotSerializableException is thrown, at least caller got some info.

        List<Throwable> chain = new ArrayList<Throwable>(8);
        // Element zero is root cause.
        collectChain(chain, t);

        ObjectOutput out = mOut;
        out.writeObject(InvocationInputStream.localAddress(mChannel));
        out.writeObject(InvocationInputStream.remoteAddress(mChannel));

        writeVarUnsignedInt(chain.size());

        for (int i=0; i<chain.size(); i++) {
            Throwable sub = chain.get(i);
            out.writeObject(sub.getClass().getName());
            out.writeObject(sub.getMessage());
            StackTraceElement[] trace = sub.getStackTrace();
            if (cPruneStackTraces) {
                trace = prune(trace);
                sub.setStackTrace(trace);
            }
            out.writeObject(trace);
        }

        // Ensure caller gets something before we try to serialize the whole Throwable.
        out.flush();

        // Write the Throwable in all its glory.
        out.writeObject(t);
    }

    private static void collectChain(List<Throwable> chain, Throwable t) {
        Throwable cause = t.getCause();
        if (cause != null) {
            collectChain(chain, cause);
        }
        chain.add(t);
    }

    private static StackTraceElement[] prune(StackTraceElement[] trace) {
        // Prune everything after the skeleton class.

        int i;
        for (i=0; i<trace.length; i++) {
            if (SKELETON_GENERATOR_NAME.equals(trace[i].getFileName())) {
                break;
            }
        }

        if (i > 0 && i < trace.length) {
            return Arrays.copyOfRange(trace, 0, i + 1);
        }

        return trace;
    }

    public void reset() throws IOException {
        mOut.reset();
    }

    public void flush() throws IOException {
        mOut.flush();
    }

    @Override
    public String toString() {
        if (mChannel == null) {
            return super.toString();
        }
        return "OutputStream for ".concat(mChannel.toString());
    }

    public void close() throws IOException {
        if (mChannel == null) {
            mOut.close();
        } else {
            mChannel.close();
        }
    }

    void doDrain() throws IOException {
        mOut.drain();
    }

    void doClose() throws IOException {
        mOut.close();
    }
}
