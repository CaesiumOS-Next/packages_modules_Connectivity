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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Represents a Thread network specific failure. @hide */
@SystemApi
public class ThreadNetworkException extends Exception {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR_INTERNAL_ERROR, ERROR_ABORTED, ERROR_TIMEOUT, ERROR_FAILED_PRECONDITION})
    public @interface ErrorCode {}

    /** The operation failed with internal errors. */
    public static final int ERROR_INTERNAL_ERROR = 1;

    /** The operation was aborted because concurrent operations are overriding this one. */
    public static final int ERROR_ABORTED = 2;

    /** The operation failed because a deadline expired before the operation could complete. */
    public static final int ERROR_TIMEOUT = 3;

    /** The operation failed because required preconditions are not satisfied. */
    public static final int ERROR_FAILED_PRECONDITION = 4;

    private final int mErrorCode;

    /** Creates a new {@link ThreadNetworkException} object with given error code and message. */
    public ThreadNetworkException(@ErrorCode int errorCode, @NonNull String errorMessage) {
        super(errorMessage);
        requireNonNull(errorMessage, "errorMessage cannot be null");
        this.mErrorCode = errorCode;
    }

    /** Returns the error code. */
    public @ErrorCode int getErrorCode() {
        return mErrorCode;
    }
}
