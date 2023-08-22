/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes the information of a stub network.
 *
 * <p>Note that this is just a holder of data. Modifying it does not affect live networks.
 *
 * @hide
 */
@SystemApi
public final class StubNetworkInfo implements Parcelable {
    /** @hide */
    @SystemApi
    public StubNetworkInfo() {}

    /** @hide */
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof StubNetworkInfo)) {
            return false;
        } else {
            StubNetworkInfo otherStubNetworkInfo = (StubNetworkInfo) other;
            return true;
        }
    }

    public static final @NonNull Creator<StubNetworkInfo> CREATOR =
            new Creator<StubNetworkInfo>() {
                public StubNetworkInfo createFromParcel(Parcel in) {
                    return new StubNetworkInfo();
                }

                public StubNetworkInfo[] newArray(int size) {
                    return new StubNetworkInfo[size];
                }
            };
}
