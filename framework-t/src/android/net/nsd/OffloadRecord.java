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

package android.net.nsd;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * The class represents the information that need to be passed to hardware offload handler.
 * <p>
 * It contains a service name and service type pairs to be offloaded.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class OffloadRecord implements Parcelable {
    // The labels for the service name (e.g. "NsdChat")
    @NonNull
    private final String mServiceName;
    // The labels for the service type (e.g. ["http", "tcp", "local"] )
    @NonNull
    private final String[] mServiceType;

    // The labels of device host name (e.g. ["Android", "local"] )
    @NonNull
    private final String[] mDeviceHostName;

    // Raw response for hardware offloading.
    // It is null of the OffloadEngine can not handle retransmit.
    @Nullable
    private final byte[] mRawOffloadPacket;

    public OffloadRecord(@NonNull String serviceName, @NonNull String[] serviceType,
            @NonNull String[] deviceHostName, @Nullable byte[] rawOffloadPacket) {
        mServiceName = serviceName;
        mServiceType = serviceType;
        mDeviceHostName = deviceHostName;
        mRawOffloadPacket = rawOffloadPacket;
    }

    /**
     * @hide
     */
    protected OffloadRecord(@NonNull Parcel in) {
        mServiceName = in.readString();
        int serviceTypeSize = in.readInt();
        mServiceType = new String[serviceTypeSize];
        in.readStringArray(mServiceType);
        int deviceHostNameSize = in.readInt();
        mDeviceHostName = new String[deviceHostNameSize];
        in.readStringArray(mDeviceHostName);
        int rawOffloadPacketSize = in.readInt();
        mRawOffloadPacket = new byte[rawOffloadPacketSize];
        in.readByteArray(mRawOffloadPacket);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeInt(mServiceType.length);
        dest.writeStringArray(mServiceType);
        dest.writeInt(mDeviceHostName.length);
        dest.writeStringArray(mDeviceHostName);
        dest.writeInt(mRawOffloadPacket.length);
        dest.writeByteArray(mRawOffloadPacket);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<OffloadRecord> CREATOR = new Creator<OffloadRecord>() {
        @Override
        public OffloadRecord createFromParcel(Parcel in) {
            return new OffloadRecord(in);
        }

        @Override
        public OffloadRecord[] newArray(int size) {
            return new OffloadRecord[size];
        }
    };

    @NonNull
    public String getServiceName() {
        return mServiceName;
    }

    @NonNull
    public String[] getServiceType() {
        return mServiceType;
    }

    @NonNull
    public String[] getDeviceHostName() {
        return mDeviceHostName;
    }

    @NonNull
    public byte[] getRawOffloadPacket() {
        return mRawOffloadPacket;
    }

    @Override
    public String toString() {
        return "OffloadRecord{"
                + "mServiceName='" + mServiceName + '\''
                + ", mServiceType=" + Arrays.toString(mServiceType)
                + ", mDeviceHostName=" + Arrays.toString(mDeviceHostName)
                + ", mRawOffloadPacket=" + Arrays.toString(mRawOffloadPacket)
                + '}';
    }
}
