/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;

/**
 * A service to do the Carrier Tethering Entitlement check.
 */
public abstract class TetheringEntitlementService extends Service {
    private static final String TAG = "TetheringEntitlementService";

    /**
     * The {@link Intent} action that must be declared as handled by a service in its manifest
     * for the system to recognize it as a Tethering Entitlement provider service.
     */
    public static final String SERVICE_INTERFACE =
            "android.net.TetheringEntitlementService";

    private TetheringEntitlementServiceWrapper mWrapper;

    @Override
    public void onCreate() {
        super.onCreate();
        mWrapper = new TetheringEntitlementServiceWrapper();
    }

    /**
     * Requests Tethering Entitlement check.
     */
    public abstract void onTetheringEntitlementcheck(int subscriptionId, int type,
            @NonNull TetheringEntitlementResultCallback resultCallback);

    /** @hide */
    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        return mWrapper;
    }

    /**
     * A wrapper around {@link android.net.ITetheringEntitlementService} that forwards calls
     * to implementations of {@link android.net.TetheringEntitlementService}.
     */
    private final class TetheringEntitlementServiceWrapper
            extends ITetheringEntitlementService.Stub {
        @Override
        public void doEntitlementCheck(int subId, int tetheringType, RemoteCallback callback) {
            TetheringEntitlementService.this.onTetheringEntitlementcheck(
                    subId, tetheringType, new TetheringEntitlementResultCallback() {
                        @Override
                        public void onTetheringEntitlemntResult(int resultCode,
                                CharSequence dialogContent) {
                            Bundle result = new Bundle();
                            result.putInt(
                                    TetheringEntitlementResultCallback
                                            .EXTRA_TETHERING_ENTITLEMENT_RESULT_CODE,
                                    resultCode);
                            result.putCharSequence(
                                    TetheringEntitlementResultCallback
                                            .EXTRA_TETHERING_ENTITLEMENT_DIALOG_CONTENT,
                                    dialogContent);
                                callback.sendResult(result);
                        }
                    });
        }
    }
}
