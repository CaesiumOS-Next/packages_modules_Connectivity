/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.server.remoteauth.connectivity;

import java.util.Objects;

/**
 * Encapsulates the connection information.
 *
 * <p>Connection information captures the details of underlying connection such as connection id,
 * type of connection and peer device mac address.
 *
 * @hide
 */
// TODO(b/295407748) Change to use @DataClass.
public class BaseConnectionInfo {
    int mConnectionId;

    BaseConnectionInfo(int connectionId) {
        mConnectionId = connectionId;
    }

    public int getConnectionId() {
        return mConnectionId;
    }

    /** Returns string representation of BaseConnectionInfo. */
    @Override
    public String toString() {
        return "ConnectionInfo[" + "connectionId= " + mConnectionId + "]";
    }

    /** Returns true if this ConnectionInfo object is equal to the other. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BaseConnectionInfo)) {
            return false;
        }

        BaseConnectionInfo other = (BaseConnectionInfo) o;
        return mConnectionId == other.getConnectionId();
    }

    /** Returns the hashcode of this object */
    @Override
    public int hashCode() {
        return Objects.hash(mConnectionId);
    }
}
