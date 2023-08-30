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

import static com.android.internal.util.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * The timestamp of Thread Operational Dataset.
 *
 * @see ActiveOperationalDataset
 * @see PendingOperationalDataset
 * @hide
 */
@SystemApi
public final class OperationalDatasetTimestamp {
    /** @hide */
    public static final int LENGTH_TIMESTAMP = Long.BYTES;

    private final long encodedValue;

    /**
     * Creates a new {@link OperationalDatasetTimestamp} object from an {@link Instant}. The {@code
     * ticks} is set to {@code zero} and {@code isAuthoritativeSource} is {@code true}.
     *
     * @throws IllegalArgumentException if {@code instant.getSeconds()} is larger than {@code
     *     0xffffffffffffL}
     */
    @NonNull
    public static OperationalDatasetTimestamp fromInstant(@NonNull Instant instant) {
        requireNonNull(instant, "instant cannot be null");
        return new OperationalDatasetTimestamp(
                instant.getEpochSecond(), /* ticks= */ 0, /* isAuthoritativeSource */ true);
    }

    /** Converts this {@link OperationalDatasetTimestamp} object to an {@link Instant}. */
    @NonNull
    public Instant toInstant() {
        return Instant.ofEpochSecond(getSeconds());
    }

    /**
     * Creates a new {@link OperationalDatasetTimestamp} object from the OperationalDatasetTimestamp
     * TLV value.
     *
     * @hide
     */
    @NonNull
    public static OperationalDatasetTimestamp fromTlvValue(@NonNull byte[] encodedTimestamp) {
        requireNonNull(encodedTimestamp, "encodedTimestamp cannot be null");
        checkArgument(
                encodedTimestamp.length == LENGTH_TIMESTAMP,
                "Invalid Thread OperationalDatasetTimestamp length (length = %d,"
                        + " expectedLength=%d)",
                encodedTimestamp.length,
                LENGTH_TIMESTAMP);
        return new OperationalDatasetTimestamp(ByteBuffer.wrap(encodedTimestamp).getLong());
    }

    /**
     * Converts this {@link OperationalDatasetTimestamp} object to Thread TLV value.
     *
     * @hide
     */
    @NonNull
    public byte[] toTlvValue() {
        byte[] tlv = new byte[LENGTH_TIMESTAMP];
        ByteBuffer buffer = ByteBuffer.wrap(tlv);
        buffer.putLong(encodedValue);
        return tlv;
    }

    /**
     * Creates a new {@link OperationalDatasetTimestamp} object.
     *
     * @param seconds the value encodes a Unix Time value. Must be in the range of
     *     0x0-0xffffffffffffL
     * @param ticks the value encodes the fractional Unix Time value in 32.768 kHz resolution. Must
     *     be in the range of 0x0-0x7fff
     * @param isAuthoritativeSource the flag indicates the time was obtained from an authoritative
     *     source: either NTP (Network Time Protocol), GPS (Global Positioning System), cell
     *     network, or other method
     * @throws IllegalArgumentException if the {@code seconds} is not in range of
     *     0x0-0xffffffffffffL or {@code ticks} is not in range of 0x0-0x7fff
     */
    public OperationalDatasetTimestamp(
            @IntRange(from = 0x0, to = 0xffffffffffffL) long seconds,
            @IntRange(from = 0x0, to = 0x7fff) int ticks,
            boolean isAuthoritativeSource) {
        this((seconds << 16) | (ticks << 1) | (isAuthoritativeSource ? 1 : 0));
        checkArgument(
                seconds >= 0 && seconds <= 0xffffffffffffL,
                "OperationalDatasetTimestamp seconds exceeds allowed range (seconds = %d,"
                        + " allowedRange = [0x0, 0xffffffffffffL])",
                seconds);
        checkArgument(
                ticks >= 0 && ticks <= 0x7fff,
                "OperationalDatasetTimestamp ticks exceeds allowed ranged (ticks = %d, allowedRange"
                        + " = [0x0, 0x7fff])",
                ticks);
    }

    private OperationalDatasetTimestamp(long encodedValue) {
        this.encodedValue = encodedValue;
    }

    /** Returns the seconds portion of the timestamp. */
    public @IntRange(from = 0x0, to = 0xffffffffffffL) long getSeconds() {
        return (encodedValue >> 16) & 0x0000ffffffffffffL;
    }

    /** Returns the ticks portion of the timestamp. */
    public @IntRange(from = 0x0, to = 0x7fff) int getTicks() {
        return (int) ((encodedValue >> 1) & 0x7fffL);
    }

    /** Returns {@code true} if the timestamp comes from an authoritative source. */
    public boolean isAuthoritativeSource() {
        return (encodedValue & 0x01) != 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{seconds=")
                .append(getSeconds())
                .append(", ticks=")
                .append(getTicks())
                .append(", isAuthoritativeSource=")
                .append(isAuthoritativeSource())
                .append(", instant=")
                .append(toInstant())
                .append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof OperationalDatasetTimestamp)) {
            return false;
        } else {
            return encodedValue == ((OperationalDatasetTimestamp) other).encodedValue;
        }
    }

    @Override
    public int hashCode() {
        return Long.hashCode(encodedValue);
    }
}
