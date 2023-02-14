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
 *
 * It contains a service name and service type pairs to be offloaded.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class OffloadInfo implements Parcelable {
    @NonNull
    private final String mServiceName;
    @NonNull
    private final String mServiceType;

    public OffloadInfo(@NonNull String serviceName, @NonNull String serviceType) {
        mServiceName = serviceName;
        mServiceType = serviceType;
    }

    /** @hide */
    protected OffloadInfo(@NonNull Parcel in) {
        mServiceName = in.readString();
        mServiceType = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mServiceType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<OffloadInfo> CREATOR = new Creator<OffloadInfo>() {
        @Override
        public OffloadInfo createFromParcel(Parcel in) {
            return new OffloadInfo(in);
        }

        @Override
        public OffloadInfo[] newArray(int size) {
            return new OffloadInfo[size];
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

    @Override
    public String toString() {
        return "OffloadInfo " + mServiceName + "." + mServiceType;
    }
}
