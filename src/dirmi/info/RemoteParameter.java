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

package dirmi.info;

import java.io.Serializable;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface RemoteParameter extends Serializable {
    /**
     * Returns true if parameter is remote, false if serialized.
     */
    boolean isRemote();

    /**
     * If parameter is remote, then call this method to get RemoteInfo. If null
     * is returned, then parameter is not remote.
     */
    RemoteInfo getRemoteInfoType();

    /**
     * If parameter is serialzied, then call this method to get the type. If
     * null is returned, then parameter is not serialized.
     */
    Class<?> getSerializedType();
}
