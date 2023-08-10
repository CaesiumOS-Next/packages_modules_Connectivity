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

package android.remoteauth;

import android.annotation.NonNull;

/**
 * Reports newly registered {@link RemoteAuthenticator}.
 *
 * @hide
 */
// TODO(b/290092977): Add back after M-2023-11 release - @SystemApi(client = MODULE_LIBRARIES)
public interface RegistrationCallback {
    /**
     * A callback function invoked upon successful registration completion.
     *
     * @param remoteAuthenticator newly registered remote authenticator
     */
    void onRegistered(@NonNull RemoteAuthenticator remoteAuthenticator);

    /**
     * A callback function invoked upon unsuccessful registration attempt.
     *
     * @param errorCode the code of the error
     * @param errString the error message
     */
    void onFailure(int errorCode, @NonNull CharSequence errString);
}
