/*
 *  Copyright 2006 Brian S O'Neill
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

package dirmi;

import java.lang.annotation.*;

/**
 * Identify a method as being asynchronous, which does not imply
 * non-blocking. It merely means that the caller does not wait for the remote
 * method to finish. An asynchronous method will likely block if the network
 * layer is backed up.
 *
 * <p>An asynchronous method must return void, and it may not declare any
 * exceptions other than {@link java.rmi.RemoteException}. An asynchronous method
 * cannot have a {@link ResponseTimeout} defined, as it is meaningless.
 *
 * @author Brian S O'Neill
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Asynchronous {
}
