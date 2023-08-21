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

package com.android.server.remoteauth.jni;

public class NativeRemoteAuthJavaPlatform {
    private static final String TAG = NativeRemoteAuthJavaPlatform.class.getSimpleName();
    public final Object mNativeLock = new Object();

    // Constructor should receive pointers to:
    // ConnectivityManager, RangingManager and DB
    public NativeRemoteAuthJavaPlatform() {
        System.loadLibrary("remoteauth_jni_rust");
        synchronized (mNativeLock) {
            native_init();
        }
    }

    public void sendRequest(int connection_id,
            byte[] request,
            long response_handler,
            long platform_handler) {
        // TODO: Call ConnectivityManager
        // TODO: Need to figure out if to change a trait, connection_id may not be enough!

//        Connection connection = mConnectivityManager.connect(new BaseConnectionInfo(connection_id),
//                connectionInfo -> {
//            // do something if you want
//                });
//        connection.sendMessage(connection_id, request, new MessageResponseCallback() {
//            public void onSuccess(byte[] buffer) {
//                synchronized (mNativeLock) {
//                    native_on_send_request_success(buffer, platform_handler, response_handler);
//                }
//            }
//
//            /**
//             * Called when message sending fails.
//             *
//             * @param errorCode indicating the error.
//             */
//            public void onFailure(@ErrorCode int errorCode) {
//                synchronized (mNativeLock) {
//                    native_on_send_request_error(errorCode, platform_handler, response_handler);
//                }
//            }
//        });
    }

    /* Native functions implemented in JNI */
    // This function should be implemented in remoteauth_jni_android_protocol
    private native boolean native_init();

    private native void native_on_send_request_success(byte[] app_response,
            long platform_handler,
            long response_handler);

    private native void native_on_send_request_error(int error_code,
            long platform_handler,
            long response_handler);
}
