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
 * The OffloadServiceInfo class contains all the necessary information the OffloadEngine need to
 * know about how to offload a mDns service.
 *
 * @hide
 */
// @SystemApi
public final class OffloadServiceInfo implements Parcelable {
    @NonNull
    private final String mServiceName;
    @NonNull
    private final String[] mServiceType;
    @NonNull
    private final String[] mDeviceHostName;
    @Nullable
    private final byte[] mRawOffloadPacket;

    private final int mPriority;

    /**
     * Creates a new OffloadServiceInfo object with the specified parameters.
     *
     * @param serviceName The name of the service.
     * @param serviceType The types of the service.
     * @param deviceHostName The host names of the device.
     * @param rawOffloadPacket The raw response for hardware offloading.
     * @param priority The priority of the service.
     */
    public OffloadServiceInfo(@NonNull String serviceName, @NonNull String[] serviceType,
            @NonNull String[] deviceHostName, @Nullable byte[] rawOffloadPacket, int priority) {
        mServiceName = serviceName;
        mServiceType = serviceType;
        mDeviceHostName = deviceHostName;
        mRawOffloadPacket = rawOffloadPacket;
        mPriority = priority;
    }

    /**
     * Creates a new OffloadServiceInfo object from a Parcel.
     *
     * @param in The Parcel to read the object from.
     *
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

    /**
     * Get the service name. (e.g. "NsdChat")
     */
    @NonNull
    public String getServiceName() {
        return mServiceName;
    }

    /**
     * Get the service type. (e.g. ["_http", "_tcp", "local"] )
     */
    @NonNull
    public String[] getServiceType() {
        return mServiceType;
    }

    /**
     * Get the host name. (e.g. ["Android", "local"] )
     */
    @NonNull
    public String[] getDeviceHostName() {
        return mDeviceHostName;
    }

    /**
     * Get the raw packet that the OffloadEngine can use to directly reply the incoming query.
     * It is null if the OffloadEngine can not handle transmit.
     */
    @Nullable
    // The packet must be sent as-is when replying to query.
    public byte[] getRawOffloadPacket() {
        return mRawOffloadPacket;
    }

    /**
     * Get The priority for the OffloadServiceInfo. When OffloadEngine don't have enough resource
     * (e.g. not enough memory) to offload all the OffloadServiceInfo. The OffloadServiceInfo
     * have small priority value should be handled by the OffloadEngine first.
     */
    public int getPriority() {
        return mPriority;
    }

    /**
     * Only for debug purpose. The string can be long as the raw packet is dump in the string.
     */
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
