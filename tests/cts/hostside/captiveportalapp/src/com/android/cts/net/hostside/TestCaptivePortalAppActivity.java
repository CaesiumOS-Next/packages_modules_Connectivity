/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.cts.net.hostside;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.ResultReceiver;
import android.os.ServiceSpecificException;
import android.system.OsConstants;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

public class TestCaptivePortalAppActivity extends Activity {
    private static final String TAG = "TestCaptivePortalAppActivity";
    private CaptivePortal mCaptivePortal;
    private Network mNetwork;
    private ComponentName mComponentName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCaptivePortal = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);
        mNetwork = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
        mComponentName = getIntent().getComponent();

        final Bundle bundle = getIntent().getExtras();
        final int uid = bundle.getInt("uid");
        final ResultReceiver receiver = bundle.getParcelable("receiver");

        final OutcomeReceiver<Void, ServiceSpecificException> outcomeReceiver =
                new OutcomeReceiver<Void, ServiceSpecificException>() {
            @Override
            public void onResult(Void r) {
                Log.d(TAG, "Set delegate uid for " + mComponentName.getPackageName()
                        + " to bypass VPN successfully");
                receiver.send(0, null);
            }
            @Override
            public void onError(ServiceSpecificException e) {
                Log.e(TAG, "Fail to set delegate uid for "
                        + mComponentName.getPackageName() + " to bypass VPN"
                        + ", error: " + OsConstants.errnoName(e.errorCode), e);
                receiver.send(-1, null);
            }
        };

        final Class captivePortalClass = mCaptivePortal.getClass();
        try {
            final Method setDelegateUidMethod =
                    captivePortalClass.getMethod("setDelegateUid", int.class, Executor.class,
                            OutcomeReceiver.class);
            setDelegateUidMethod.invoke(mCaptivePortal,
                    uid,
                    getMainExecutor(),
                    outcomeReceiver);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            Log.e(TAG, "Reflection exception while setting delegate uid", e);
        }
    }
}