/*
 *  Copyright 2007-2010 Brian S O'Neill
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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.cojen.dirmi.info.RemoteInfo;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class MarshalledRemote implements Marshalled, Externalizable {
    private static final long serialVersionUID = 1;

    VersionedIdentifier mObjId;
    int mObjVersion;
    VersionedIdentifier mTypeId;
    int mTypeVersion;
    RemoteInfo mInfo;

    // Need public constructor for Externalizable.
    public MarshalledRemote() {
    }

    MarshalledRemote(VersionedIdentifier objId, VersionedIdentifier typeId, RemoteInfo info) {
        mObjId = objId;
        mTypeId = typeId;
        mInfo = info;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        mObjId.writeWithNextVersion(out);
        mTypeId.writeWithNextVersion(out);
        out.writeObject(mInfo);
    }

    /**
     * Must call updateRemoteVersions after using object and type.
     */
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        mObjId = VersionedIdentifier.read(in);
        mObjVersion = in.readInt();
        mTypeId = VersionedIdentifier.read(in);
        mTypeVersion = in.readInt();
        mInfo = (RemoteInfo) in.readObject();
    }

    public void updateRemoteVersions() {
        mObjId.updateRemoteVersion(mObjVersion);
        mTypeId.updateRemoteVersion(mTypeVersion);
    }
}
