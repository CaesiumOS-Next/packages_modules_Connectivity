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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.PacProxyManager;
import android.net.Proxy;
import android.net.ProxyInfo;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;
import android.webkit.cts.TestWebServer;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class PacProxyManagerTest {
    private static final String TAG = PacProxyManagerTest.class.getSimpleName();
    private static final int PROXY_CHANGED_BROADCAST_TIMEOUT_MS = 5000;
    private static final int CALLBACK_TIMEOUT_MS = 3 * 1000;

    private Context mContext;
    private TestWebServer mServer;
    private ConnectivityManager mCm;
    private PacProxyManager mPacProxyManager;
    private ServerSocket mServerSocket;
    private Instrumentation mInstrumentation;

    private static final String PAC_FILE = "function FindProxyForURL(url, host)"
            + "{"
            + "  return \"PROXY 192.168.0.1:9091\";"
            + "}";

    private void adoptShellPermissionIdentity() {
        final UiAutomation uiAutomation =
                InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity();
    }

    private void dropShellPermissionIdentity() {
        final UiAutomation uiAutomation =
                InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.dropShellPermissionIdentity();
    }

    @Before
    public void setUp() throws Exception {
        adoptShellPermissionIdentity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();

        mCm = (ConnectivityManager) mContext.getSystemService(ConnectivityManager.class);
        mPacProxyManager = (PacProxyManager) mContext.getSystemService(PacProxyManager.class);
        mServer = new TestWebServer(false);
    }

    @After
    public void tearDown() throws Exception {
        if (mServer != null) {
            mServer.shutdown();
            mServer = null;
        }
        dropShellPermissionIdentity();
    }

    private class TestPacProxyInstalledListener implements
            PacProxyManager.PacProxyInstalledListener {
        private final CountDownLatch mLatch = new CountDownLatch(1);

        public void onPacProxyInstalled(Network network, ProxyInfo proxy) {
            Log.e(TAG, "onPacProxyInstalled is called.");
            mLatch.countDown();
        }

        public boolean waitForCallback() throws Exception {
            final boolean result = mLatch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result;
        }
    }

    private class ProxyBroadcastReceiver extends BroadcastReceiver {
        private final CountDownLatch mLatch = new CountDownLatch(2);
        private final ProxyInfo mProxy;

        ProxyBroadcastReceiver(ProxyInfo proxy) {
            mProxy = proxy;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final ProxyInfo proxy = (ProxyInfo) intent.getExtra(Proxy.EXTRA_PROXY_INFO,
                    ProxyInfo.buildPacProxy(Uri.EMPTY));
            final boolean res = mProxy.equals(proxy);

            // ProxyTracker sends sticky broadcast which will receive the last broadcast while
            // register the intent receiver so here verifies the second intent.
            if (mLatch.getCount() == 0) {
                assertEquals(mProxy.getPacFileUrl(), proxy.getPacFileUrl());
            }
            mLatch.countDown();
        }

        public boolean waitForProxyChanged() throws Exception {
            final boolean result = mLatch.await(PROXY_CHANGED_BROADCAST_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
            return result;
        }
    }

    @Test
    public void testSetCurrentProxyScriptUrl() throws Exception {
        // Register a PacProxyInstalledListener
        final TestPacProxyInstalledListener listener = new TestPacProxyInstalledListener();
        final Executor executor = (Runnable r) -> r.run();

        mPacProxyManager.addPacProxyInstalledListener(executor, listener);

        final List<Pair<String, String>> header = new ArrayList<Pair<String, String>>();
        header.add(Pair.create("Content-Type", "application/x-ns-proxy-autoconfig"));
        mServer.setResponse("/proxy.pac", PAC_FILE, header);
        final Uri pacProxyUri = Uri.parse(mServer.getResponseUrl("/proxy.pac"));

        final ProxyInfo proxy = ProxyInfo.buildPacProxy(pacProxyUri);
        final ProxyBroadcastReceiver receiver = new ProxyBroadcastReceiver(proxy);
        mContext.registerReceiver(receiver, new IntentFilter(Proxy.PROXY_CHANGE_ACTION));

        // Call setCurrentProxyScriptUrl with the URL of the pac file.
        mPacProxyManager.setCurrentProxyScriptUrl(proxy);

        // Make sure the listener was called and testing the intent is received.
        try {
            assertTrue("Didn't receive PROXY_CHANGE_ACTION broadcast.",
                    receiver.waitForProxyChanged());
            assertTrue("Did not receive onPacProxyInstalled callback.",
                    listener.waitForCallback());
        } finally {
            mPacProxyManager.removePacProxyInstalledListener(listener);
            mContext.unregisterReceiver(receiver);
        }
    }
}
