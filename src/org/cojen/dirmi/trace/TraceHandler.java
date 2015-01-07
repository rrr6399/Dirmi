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

/**
 * A trace handler is responsible for gathering and processing traced method
 * invocations. Implementations must have a public constructor which accepts a {@link
 * TraceToolbox} instance, and it may also accept a String argument.
 *
 * <pre>
 * public class MyHandler implements TraceHandler {
 *     public MyHandler(TraceToolbox toolbox, String arg) {
 *         ...
 *     }
 *
 *     ...
 * }
 * </pre>
 *
 * @author Brian S O'Neill
 * @see org.cojen.dirmi.Trace
 * @see TraceAgent
 */
public interface TraceHandler {
    /**
     * Return allowed tracing modes for the given class.
     *
     * @param className full name of class which may contain traced methods
     * @return modes or null if none allowed
     */
    TraceModes getTraceModes(String className);

    /**
     * Called by a traced method upon entry.
     *
     * @param mid method id
     */
    void enterMethod(int mid);

    /**
     * Called by a traced method upon entry. This method is never called if the
     * trace was not configured to pass arguments or if the traced method has
     * no arguments.
     *
     * @param mid method id
     * @param argument argument passed to method; is "this" for non-static method
     */
    void enterMethod(int mid, Object argument);

    /**
     * Called by a traced method upon entry. This method is never called if the
     * trace was not configured to pass arguments or if the traced method has
     * no arguments.
     *
     * @param mid method id
     * @param arguments arguments passed to method; first is "this" for non-static method
     */
    void enterMethod(int mid, Object... arguments);

    /**
     * Called by a traced method upon exit.
     *
     * @param mid method id
     */
    void exitMethod(int mid);

    /**
     * Called by a traced method upon exit. This method is never called if the
     * trace was not configured to measure execution time.
     *
     * @param mid method id
     * @param timeNanos total execution time of method, in nanoseconds
     */
    void exitMethod(int mid, long timeNanos);

    /**
     * Called by a traced method upon exit. This method is never called if the
     * trace was not configured to pass return values or if the traced method
     * returns void.
     *
     * @param mid method id
     * @param result method return value
     */
    void exitMethod(int mid, Object result);

    /**
     * Called by a traced method upon exit. This method is never called if the
     * trace was not configured to pass return values and measure execution
     * time.
     *
     * @param mid method id
     * @param result method return value
     * @param timeNanos total execution time of method, in nanoseconds
     */
    void exitMethod(int mid, Object result, long timeNanos);

    /**
     * Called by a traced method upon exit caused by a thrown exception. This
     * method is never called if the trace was not configured to pass thrown
     * exceptions.
     *
     * @param mid method id
     * @param t exception thrown by method
     */
    void exitMethod(int mid, Throwable t);

    /**
     * Called by a traced method upon exit caused by a thrown exception. This
     * method is never called if the trace was not configured to pass thrown
     * exceptions and measure execution time.
     *
     * @param mid method id
     * @param t exception thrown by method
     * @param timeNanos total execution time of method, in nanoseconds
     */
    void exitMethod(int mid, Throwable t, long timeNanos);
}
