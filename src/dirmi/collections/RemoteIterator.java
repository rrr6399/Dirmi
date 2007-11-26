/*
 *  Copyright 2007 Brian S O'Neill
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

package dirmi.collections;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteIterator<E> extends Remote {
    boolean hasNext() throws RemoteException;

    E next() throws RemoteException;

    /**
     * @param type element type
     * @param max max amount to fetch
     * @return null or smaller than max amount if end is reached
     */
    E[] next(Class<? extends E> type, int max) throws RemoteException;

    void remove() throws RemoteException;
}