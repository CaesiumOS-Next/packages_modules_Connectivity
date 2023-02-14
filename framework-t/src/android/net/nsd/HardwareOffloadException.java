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
 * TODO: document
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class HardwareOffloadException extends Exception implements Parcelable {
    private final int mErrorCode;
    @NonNull
    private final OffloadInfo mOffloadRequest;


    /**
     * TODO: document (and define error codes)
     * @param errorCode
     * @param offloadRequest
     */
    public HardwareOffloadException(int errorCode, @NonNull OffloadInfo offloadRequest) {
        super("Error enabling offload for " + offloadRequest + ": " + errorCode);
        mErrorCode = errorCode;
        mOffloadRequest = offloadRequest;
    }

    /** @hide */
    protected HardwareOffloadException(@NonNull Parcel in) {
        this(in.readInt(), in.readParcelable(null, OffloadInfo.class));
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mErrorCode);
        dest.writeParcelable(mOffloadRequest, 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<HardwareOffloadException> CREATOR =
            new Creator<>() {
                @Override
                public HardwareOffloadException createFromParcel(Parcel in) {
                    return new HardwareOffloadException(in);
                }

                @Override
                public HardwareOffloadException[] newArray(int size) {
                    return new HardwareOffloadException[size];
                }
            };
}
