/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** An mDNS "SRV" record, which contains service information. */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
@VisibleForTesting
public class MdnsServiceRecord extends MdnsRecord {
    public static final int PROTO_NONE = 0;
    public static final int PROTO_TCP = 1;
    public static final int PROTO_UDP = 2;
    private static final String PROTO_TOKEN_TCP = "_tcp";
    private static final String PROTO_TOKEN_UDP = "_udp";
    private int mServicePriority;
    private int mServiceWeight;
    private int mServicePort;
    private String[] mServiceHost;

    public MdnsServiceRecord(String[] name, MdnsPacketReader reader) throws IOException {
        super(name, TYPE_SRV, reader);
    }

    /** Returns the service's port number. */
    public int getServicePort() {
        return mServicePort;
    }

    /** Returns the service's host name. */
    public String[] getServiceHost() {
        return mServiceHost;
    }

    /** Returns the service's priority. */
    public int getServicePriority() {
        return mServicePriority;
    }

    /** Returns the service's weight. */
    public int getServiceWeight() {
        return mServiceWeight;
    }

    // Format of name is <instance-name>.<service-name>.<protocol>.<domain>

    /** Returns the service's instance name, which uniquely identifies the service instance. */
    public String getServiceInstanceName() {
        if (mName.length < 1) {
            return null;
        }
        return mName[0];
    }

    /** Returns the service's name. */
    public String getServiceName() {
        if (mName.length < 2) {
            return null;
        }
        return mName[1];
    }

    /** Returns the service's protocol. */
    public int getServiceProtocol() {
        if (mName.length < 3) {
            return PROTO_NONE;
        }

        String protocol = mName[2];
        if (protocol.equals(PROTO_TOKEN_TCP)) {
            return PROTO_TCP;
        }
        if (protocol.equals(PROTO_TOKEN_UDP)) {
            return PROTO_UDP;
        }
        return PROTO_NONE;
    }

    @Override
    protected void readData(MdnsPacketReader reader) throws IOException {
        mServicePriority = reader.readUInt16();
        mServiceWeight = reader.readUInt16();
        mServicePort = reader.readUInt16();
        mServiceHost = reader.readLabels();
    }

    @Override
    protected void writeData(MdnsPacketWriter writer) throws IOException {
        writer.writeUInt16(mServicePriority);
        writer.writeUInt16(mServiceWeight);
        writer.writeUInt16(mServicePort);
        writer.writeLabels(mServiceHost);
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "SRV: %s:%d (prio=%d, weight=%d)",
                labelsToString(mServiceHost),
                mServicePort,
                mServicePriority,
                mServiceWeight);
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31)
                + Objects.hash(mServicePriority, mServiceWeight, Arrays.hashCode(mServiceHost),
                mServicePort);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MdnsServiceRecord)) {
            return false;
        }
        MdnsServiceRecord otherRecord = (MdnsServiceRecord) other;

        return super.equals(other)
                && (mServicePriority == otherRecord.mServicePriority)
                && (mServiceWeight == otherRecord.mServiceWeight)
                && Objects.equals(mServiceHost, otherRecord.mServiceHost)
                && (mServicePort == otherRecord.mServicePort);
    }
}
