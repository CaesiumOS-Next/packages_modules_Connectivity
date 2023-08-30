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

package android.net.thread;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data interface for managing Thread Border Router configurations.
 *
 * @hide
 */
@FlaggedApi(ThreadNetworkFlags.FLAG_THREAD_ENABLED)
@SystemApi
public final class BorderRouterConfig implements Parcelable {
    private final boolean mBorderRouterEnabled;

    @NonNull
    public static final Creator<BorderRouterConfig> CREATOR =
            new Creator<>() {
                @Override
                public BorderRouterConfig createFromParcel(Parcel in) {
                    return new BorderRouterConfig(in);
                }

                @Override
                public BorderRouterConfig[] newArray(int size) {
                    return new BorderRouterConfig[size];
                }
            };

    private BorderRouterConfig(Parcel in) {
        this(in.readBoolean());
    }

    private BorderRouterConfig(Builder builder) {
        this(builder.mBorderRouterEnabled);
    }

    private BorderRouterConfig(boolean borderRouterEnabled) {
        this.mBorderRouterEnabled = borderRouterEnabled;
    }

    /** Returns {@code true} if the Thread Border Router feature is enabled. */
    public boolean isBorderRouterEnabled() {
        return mBorderRouterEnabled;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mBorderRouterEnabled);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        } else if (!(other instanceof BorderRouterConfig)) {
            return false;
        } else {
            BorderRouterConfig otherConfig = (BorderRouterConfig) other;
            return mBorderRouterEnabled == otherConfig.mBorderRouterEnabled;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBorderRouterEnabled);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{borderRouterEnabled=").append(mBorderRouterEnabled).append("}");
        return sb.toString();
    }

    /** The builder for creating {@link BorderRouterConfig} objects. */
    public static final class Builder {
        private boolean mBorderRouterEnabled;

        /**
         * Creates a new {@link Builder} object.
         *
         * @param borderRouterEnabled use {@code true} to enable the Border Router feature, or
         *     {@code false} to disable it
         */
        public Builder(boolean borderRouterEnabled) {
            this.mBorderRouterEnabled = borderRouterEnabled;
        }

        /**
         * Creates a new {@link Builder} object from a {@link BorderRouterConfig} object.
         *
         * @param config the Border Router configurations to be copied
         */
        public Builder(@NonNull BorderRouterConfig config) {
            requireNonNull(config, "config cannot be null");
            this.mBorderRouterEnabled = config.mBorderRouterEnabled;
        }

        /** Creates a new {@link BorderRouterConfig} object. */
        @NonNull
        public BorderRouterConfig build() {
            return new BorderRouterConfig(this);
        }
    }
}
