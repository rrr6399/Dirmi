/*
 *  Copyright 2008-2010 Brian S O'Neill
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

import java.rmi.Remote;
import java.rmi.RemoteException;

import org.cojen.dirmi.Asynchronous;
import org.cojen.dirmi.Disposer;

/**
 * Interface used internally by asynchronous remote methods to signal when
 * finished.
 *
 * @author Brian S O'Neill
 */
public interface RemoteCompletion<V> extends Remote {
    @Asynchronous
    @Disposer
    void complete(V value) throws RemoteException;

    @Asynchronous
    @Disposer
    void exception(Throwable cause) throws RemoteException;
}
