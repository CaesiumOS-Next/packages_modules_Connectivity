/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.time.Duration;
import java.util.Objects;

/**
 * Data interface for ephemeral key.
 *
 * <p>An example usage of creating an EphemeralKey:
 *
 * <pre>
 * {@code EphemeralKey ephemeralKey = new EphemeralKey.Builder("123456789", Duration.ofSeconds(120)).build();
 * }</pre>
 *
 * @hide
 */
@FlaggedApi(ThreadNetworkFlags.FLAG_EPSKC_ENABLED)
@SystemApi
public final class EphemeralKey implements Parcelable {
    private final String mPasscode;
    private final Duration mLifetime;

    private EphemeralKey(Builder builder) {
        this(builder.mPasscode, builder.mLifetime);
    }

    private EphemeralKey(String passcode, Duration lifetime) {
        this.mPasscode = passcode;
        this.mLifetime = lifetime;
    }

    /** Returns the one-time passcode for ephemeral key. */
    @NonNull
    public String getPasscode() {
        return mPasscode;
    }

    /** Returns the lifetime of ephemeral key. */
    @NonNull
    public Duration getLifetime() {
        return mLifetime;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof EphemeralKey)) {
            return false;
        } else {
            EphemeralKey otherKey = (EphemeralKey) other;
            return Objects.equals(mPasscode, otherKey.mPasscode)
                    && Objects.equals(mLifetime, otherKey.mLifetime);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPasscode, mLifetime);
    }

    @Override
    public String toString() {
        String hidePasscode = mPasscode.replaceAll(".", "*");
        return "EphemeralKey {passcode=" + hidePasscode + ", lifetime=" + mLifetime + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPasscode);
        dest.writeLong(mLifetime.toMillis());
    }

    public static final @NonNull Creator<EphemeralKey> CREATOR =
            new Creator<>() {
                @Override
                public EphemeralKey createFromParcel(Parcel in) {
                    return new EphemeralKey.Builder(
                                    in.readString(), Duration.ofMillis(in.readLong()))
                            .build();
                }

                @Override
                public EphemeralKey[] newArray(int size) {
                    return new EphemeralKey[size];
                }
            };

    /** The builder for creating {@link Configuration} objects. */
    public static final class Builder {
        private String mPasscode;
        private Duration mLifetime = Duration.ZERO;

        /**
         * Creates a new {@link Builder} object with a passcode and lifetime.
         *
         * @param passcode the ephemeral key passcode
         * @param lifetime lifetime of the ephemeral key
         */
        public Builder(@NonNull String passcode, @NonNull Duration lifetime) {
            Objects.requireNonNull(passcode);
            Objects.requireNonNull(lifetime);

            mPasscode = passcode;
            mLifetime = lifetime;
        }

        public Builder(@NonNull String passcode, long lifetimeMillis) {
            Objects.requireNonNull(passcode);

            mPasscode = passcode;
            mLifetime = Duration.ofMillis(lifetimeMillis);
        }

        /** Creates a new {@link EphemeralKey} object. */
        @NonNull
        public EphemeralKey build() {
            return new EphemeralKey(this);
        }
    }
}
