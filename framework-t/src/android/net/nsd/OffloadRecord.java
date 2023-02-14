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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The class represents the information that need to be passed to hardware offload handler.
 * <p>
 * It contains a service name and service type pairs to be offloaded.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class OffloadRecord implements Parcelable {
    @NonNull
    private final String mServiceName;
    @NonNull
    private final String mServiceType;

    @NonNull
    private final String mHostName;

    public OffloadRecord(@NonNull String serviceName, @NonNull String serviceType,
            @NonNull String hostName) {
        mServiceName = serviceName;
        mServiceType = serviceType;
        mHostName = hostName;
    }

    /**
     * @hide
     */
    protected OffloadRecord(@NonNull Parcel in) {
        mServiceName = in.readString();
        mServiceType = in.readString();
        mHostName = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mServiceType);
        dest.writeString(mHostName);
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
    public String getServiceType() {
        return mServiceType;
    }

    @NonNull
    public String getHostName() {
        return mHostName;
    }

    @Override
    public String toString() {
        return "OffloadInfo{"
                + "mServiceName='" + mServiceName + '\''
                + ", mServiceType='" + mServiceType + '\''
                + ", mHostName='" + mHostName + '\''
                + '}';

    }
}
