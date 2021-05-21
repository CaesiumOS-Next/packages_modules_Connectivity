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

package android.net.cts;

import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.ConnectivitySettingsUtils;
import com.android.networkstack.apishim.ConnectivitySettingsManagerShimImpl;
import com.android.networkstack.apishim.ConstantsShim;
import com.android.networkstack.apishim.common.ConnectivitySettingsManagerShim;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Utils class to provide common shared test helper methods or constants that behave differently
 * depending on the SDK against which they are compiled.
 */
public class TestUtils {
    private static final String TAG = TestUtils.class.getSimpleName();
    private static final int PRIVATE_DNS_SETTING_TIMEOUT_MS = 10_000;
    private static final int PRIVATE_DNS_PROBE_MS = 1_000;
    private int mOldPrivateDnsMode;
    private String mOldPrivateDnsSpecifier;
    final ConnectivitySettingsManagerShim mCsmShim =
            ConnectivitySettingsManagerShimImpl.newInstance();

    /**
     * Whether to test S+ APIs. This requires a) that the test be running on an S+ device, and
     * b) that the code be compiled against shims new enough to access these APIs.
     */
    public static boolean shouldTestSApis() {
        return SdkLevel.isAtLeastS() && ConstantsShim.VERSION > Build.VERSION_CODES.R;
    }

    public void storePrivateDnsSetting(@NonNull Context context) {
        mOldPrivateDnsMode = mCsmShim.getPrivateDnsMode(context);
        mOldPrivateDnsSpecifier = mCsmShim.getPrivateDnsHostname(context);
    }

    public void restorePrivateDnsSetting(@NonNull Context context) throws InterruptedException {
        if (mOldPrivateDnsSpecifier == null) {
            return;
        }
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        // restore private DNS setting
        if (ConnectivitySettingsUtils.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME == mOldPrivateDnsMode) {
            setPrivateDnsStrictMode(context, mOldPrivateDnsSpecifier);
            awaitPrivateDnsSetting(context, "restorePrivateDnsSetting timeout",
                    cm.getActiveNetwork(),
                    mOldPrivateDnsSpecifier, true);
        } else {
            mCsmShim.setPrivateDnsMode(context, mOldPrivateDnsMode);
        }
    }

    public void setPrivateDnsStrictMode(@NonNull Context context, String server) {
        // To reduce flake rate, set PRIVATE_DNS_SPECIFIER before PRIVATE_DNS_MODE. This ensures
        // that if the previous private DNS mode was not "hostname", the system only sees one
        // EVENT_PRIVATE_DNS_SETTINGS_CHANGED event instead of two.
        mCsmShim.setPrivateDnsHostname(context, server);
        final int mode = mCsmShim.getPrivateDnsMode(context);
        // If current private DNS mode is "hostname", we only need to set PRIVATE_DNS_SPECIFIER.
        if (mode != ConnectivitySettingsUtils.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
            mCsmShim.setPrivateDnsMode(context,
                    ConnectivitySettingsUtils.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME);
        }
    }

    public void awaitPrivateDnsSetting(@NonNull Context context, @NonNull String msg,
            @NonNull Network network, @NonNull String server,
            boolean requiresValidatedServers) throws InterruptedException {
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        CountDownLatch latch = new CountDownLatch(1);
        NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                if (requiresValidatedServers && lp.getValidatedPrivateDnsServers().isEmpty()) {
                    return;
                }
                if (network.equals(n) && server.equals(lp.getPrivateDnsServerName())) {
                    latch.countDown();
                }
            }
        };
        cm.registerNetworkCallback(request, callback);
        assertTrue(msg, latch.await(PRIVATE_DNS_SETTING_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        cm.unregisterNetworkCallback(callback);
        // Wait some time for NetworkMonitor's private DNS probe to complete. If we do not do
        // this, then the test could complete before the NetworkMonitor private DNS probe
        // completes. This would result in tearDown disabling private DNS, and the NetworkMonitor
        // private DNS probe getting stuck because there are no longer any private DNS servers to
        // query. This then results in the next test not being able to change the private DNS
        // setting within the timeout, because the NetworkMonitor thread is blocked in the
        // private DNS probe. There is no way to know when the probe has completed: because the
        // network is likely already validated, there is no callback that we can listen to, so
        // just sleep.
        if (requiresValidatedServers) {
            Thread.sleep(PRIVATE_DNS_PROBE_MS);
        }
    }
}
