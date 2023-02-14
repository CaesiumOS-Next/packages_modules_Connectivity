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

import com.android.net.module.util.HexDump;

import java.util.Arrays;
import java.util.Objects;

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
    private final String mServiceType;
    @NonNull
    private final String mDeviceHostName;
    @Nullable
    private final byte[] mRawOffloadPacket;
    private final int mPriority;
    private final long mOffloadType;

    /**
     * Creates a new OffloadServiceInfo object with the specified parameters.
     *
     * @param serviceName The name of the service.
     * @param serviceType The types of the service.
     * @param deviceHostName The host names of the device.
     * @param rawOffloadPacket The raw response for hardware offloading.
     * @param priority The priority of the service.
     * @param offloadType The type of the service offload.
     */
    public OffloadServiceInfo(@NonNull String serviceName, @NonNull String serviceType,
            @NonNull String deviceHostName, @Nullable byte[] rawOffloadPacket,
            int priority, long offloadType) {
        mServiceName = serviceName;
        mServiceType = serviceType;
        mDeviceHostName = deviceHostName;
        mRawOffloadPacket = rawOffloadPacket;
        mPriority = priority;
        mOffloadType = offloadType;
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
        mServiceType = in.readString();
        mDeviceHostName = in.readString();
        mRawOffloadPacket = in.createByteArray();
        mPriority = in.readInt();
        mOffloadType = in.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mServiceType);
        dest.writeString(mDeviceHostName);
        dest.writeByteArray(mRawOffloadPacket);
        dest.writeInt(mPriority);
        dest.writeLong(mOffloadType);
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
     * Get the service type. (e.g. "_http._tcp.local" )
     */
    @NonNull
    public String getServiceType() {
        return mServiceType;
    }

    /**
     * Get the host name. (e.g. "Android.local" )
     */
    @NonNull
    public String getDeviceHostName() {
        return mDeviceHostName;
    }

    /**
     * Get the raw packet that the OffloadEngine can use to directly reply the incoming query.
     *
     * It is null if the OffloadEngine can not handle transmit.The packet must be sent as-is when
     * replying to query.
     */
    @Nullable
    public byte[] getRawOffloadPacket() {
        if (mRawOffloadPacket == null) {
            return null;
        } else {
            return mRawOffloadPacket.clone();
        }
    }

    /**
     * Get the offloadType.
     *
     * The offloadType would be match one of the capabilities defined in
     * {@link OffloadEngine}
     */
    public long getOffloadType() {
        return mOffloadType;
    }

    /**
     * Get The priority for the OffloadServiceInfo.
     *
     * When OffloadEngine don't have enough resource
     * (e.g. not enough memory) to offload all the OffloadServiceInfo. The OffloadServiceInfo
     * have small priority value should be handled by the OffloadEngine first.
     */
    public int getPriority() {
        return mPriority;
    }

    @Override
    public String toString() {
        return "OffloadServiceInfo{" + "mServiceName='" + mServiceName + '\'' + ", mServiceType='"
                + mServiceType + '\'' + ", mDeviceHostName='" + mDeviceHostName + '\''
                + ", mRawOffloadPacket=" + HexDump.dumpHexString(mRawOffloadPacket) + ", mPriority="
                + mPriority + ", mOffloadType=" + mOffloadType
                + '}';
    }

    /**
     * Only for debug purpose, the string can be long as the raw packet is dump in the string.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OffloadServiceInfo)) return false;
        OffloadServiceInfo that = (OffloadServiceInfo) o;
        return mPriority == that.mPriority && mOffloadType == that.mOffloadType
                && mServiceName.equals(
                that.mServiceName) && mServiceType.equals(that.mServiceType)
                && mDeviceHostName.equals(
                that.mDeviceHostName) && Arrays.equals(mRawOffloadPacket,
                that.mRawOffloadPacket);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mServiceName, mServiceType, mDeviceHostName, mPriority,
                mOffloadType);
        result = 31 * result + Arrays.hashCode(mRawOffloadPacket);
        return result;
    }
}
