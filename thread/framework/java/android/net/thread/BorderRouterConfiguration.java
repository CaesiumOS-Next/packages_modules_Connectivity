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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data interface for managing Thread Border Router configurations.
 * @hide
 */
@SystemApi
public final class BorderRouterConfiguration implements Parcelable {
    private final boolean mBorderRouterEnabled;

    @NonNull
    public static final Creator<BorderRouterConfiguration> CREATOR =
            new Creator<>() {
                @Override
                public BorderRouterConfiguration createFromParcel(Parcel in) {
                    return new BorderRouterConfiguration(in);
                }

                @Override
                public BorderRouterConfiguration[] newArray(int size) {
                    return new BorderRouterConfiguration[size];
                }
            };

    private BorderRouterConfiguration(Parcel in) {
        this(in.readBoolean());
    }

    private BorderRouterConfiguration(Builder builder) {
        this(builder.mBorderRouterEnabled);
    }

    private BorderRouterConfiguration(boolean borderRouterEnabled) {
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
        } else if (!(other instanceof BorderRouterConfiguration)) {
            return false;
        } else {
            BorderRouterConfiguration otherConfig = (BorderRouterConfiguration) other;
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
        sb.append("{borderRouterEnabled=")
                .append(mBorderRouterEnabled)
                .append("}");
        return sb.toString();
    }

    /** The builder for creating {@link BorderRouterConfiguration} objects. */
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
         * Creates a new {@link Builder} object from a {@link BorderRouterConfiguration} object.
         *
         * @param config the Border Router configurations to be copied
         */
        public Builder(@NonNull BorderRouterConfiguration config) {
            requireNonNull(config, "config cannot be null");
            this.mBorderRouterEnabled = config.mBorderRouterEnabled;
        }

        /** Creates a new {@link BorderRouterConfiguration} object. */
        @NonNull
        public BorderRouterConfiguration build() {
            return new BorderRouterConfiguration(this);
        }
    }
}
