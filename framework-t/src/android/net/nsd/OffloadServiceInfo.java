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
// @SystemApi
public final class OffloadServiceInfo implements Parcelable {
    // The label for the service name (e.g. "NsdChat")
    @NonNull
    private final String mServiceName;
    // The labels for the service type (e.g. ["_http", "_tcp", "local"] )
    @NonNull
    private final String[] mServiceType;

    // The labels of device host name (e.g. ["Android", "local"] )
    @NonNull
    private final String[] mDeviceHostName;

    // Raw response for hardware offloading.
    // It is null if the OffloadEngine can not handle transmit.
    @Nullable
    private final byte[] mRawOffloadPacket;

    // The priority for the OffloadServiceInfo. Small int number means high priority. The
    // OffloadServiceInfo with higher priority number should be offloaded first.
    private int mPriority;

    public OffloadServiceInfo(@NonNull String serviceName, @NonNull String[] serviceType,
            @NonNull String[] deviceHostName, @Nullable byte[] rawOffloadPacket, int priority) {
        mServiceName = serviceName;
        mServiceType = serviceType;
        mDeviceHostName = deviceHostName;
        mRawOffloadPacket = rawOffloadPacket;
        mPriority = priority;
    }

    /**
     * @hide
     */
    public OffloadServiceInfo(@NonNull Parcel in) {
        mServiceName = in.readString();
        mServiceType = in.createStringArray();
        mDeviceHostName = in.createStringArray();
        mRawOffloadPacket = in.createByteArray();
        mPriority = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeStringArray(mServiceType);
        dest.writeStringArray(mDeviceHostName);
        dest.writeByteArray(mRawOffloadPacket);
        dest.writeInt(mPriority);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<OffloadServiceInfo> CREATOR = new Creator<OffloadServiceInfo>() {
        @Override
        public OffloadServiceInfo createFromParcel(Parcel in) {
            return new OffloadServiceInfo(in);
        }

        @Override
        public OffloadServiceInfo[] newArray(int size) {
            return new OffloadServiceInfo[size];
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

    @Nullable
    // The packet must be sent as-is when replying to query.
    public byte[] getRawOffloadPacket() {
        return mRawOffloadPacket;
    }

    // Only for debug purpose. The string can be long as the raw packet is dump in the string.
    @Override
    public String toString() {
        return "OffloadRecord{"
                + "mServiceName='" + mServiceName + '\''
                + ", mServiceType=" + Arrays.toString(mServiceType)
                + ", mDeviceHostName=" + Arrays.toString(mDeviceHostName)
                + ", mRawOffloadPacket=" + Arrays.toString(mRawOffloadPacket)
                + ", mPriority=" + mPriority
                + '}';
    }

}
