/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.tethering.mts;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.Manifest.permission.WRITE_SETTINGS;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;

import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.TetheringConfigurationParcel;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.net.cts.util.CtsTetheringUtils;
import android.net.cts.util.CtsTetheringUtils.TestTetheringEventCallback;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.ArrayTrackRecord;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TetheringModuleTest {
    // The test action has to be matched with package name to pass the check in
    // EntitlementManager#isProvisioningNeededButUnavailable.
    private static final String ENTITLEMENT_TEST_ACTION = "android.tethering.mts";
    private static final String ENTITLEMENT_TEST_RESPONSE_ACTION =
            "com.android.mts.entitlement.test.response_action";
    private static final String EXTRA_RESULT = "EntitlementResult";
    private static final String TAG = TetheringModuleTest.class.getSimpleName();
    private Context mContext;
    private CtsTetheringUtils mCtsTetheringUtils;
    private final long mRestartTimeOutMs = 5_000;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mCtsTetheringUtils = new CtsTetheringUtils(mContext);
        mContext.registerReceiver(mEntitlementResponder, new IntentFilter(ENTITLEMENT_TEST_ACTION),
                TETHER_PRIVILEGED, null /* handler */);
    }

    @After
    public void tearDown() throws Exception {
        mContext.unregisterReceiver(mEntitlementResponder);
    }

    @Test
    public void testSwitchBasePrefixRangeWhenConflict() throws Exception {
        addressConflictTest(true);
    }

    @Test
    public void testSwitchPrefixRangeWhenConflict() throws Exception {
        addressConflictTest(false);
    }

    private void addressConflictTest(final boolean wholeRangeConflict) throws Exception {
        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();

        TestNetworkTracker tnt = null;
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();

            final TetheringInterface tetheredIface =
                    mCtsTetheringUtils.startWifiTethering(tetherEventCallback);

            assertNotNull(tetheredIface);
            final String wifiTetheringIface = tetheredIface.getInterface();

            NetworkInterface nif = NetworkInterface.getByName(wifiTetheringIface);
            // Tethering downstream only have one ipv4 address.
            final LinkAddress hotspotAddr = getFirstIpv4Address(nif);
            assertNotNull(hotspotAddr);

            final IpPrefix testPrefix = getConflictingPrefix(hotspotAddr, wholeRangeConflict);
            assertNotNull(testPrefix);

            tnt = setUpTestNetwork(
                    new LinkAddress(testPrefix.getAddress(), testPrefix.getPrefixLength()));

            tetherEventCallback.expectNoTetheringActive();
            final List<String> wifiRegexs =
                    tetherEventCallback.getTetheringInterfaceRegexps().getTetherableWifiRegexs();

            final TetheringInterface restartedIface =
                    tetherEventCallback.pollTetheredInterfacesChanged(wifiRegexs, TETHERING_WIFI,
                            mRestartTimeOutMs);
            final TetheringInterface newIface;
            if (restartedIface != null) {
                newIface = restartedIface;
            } else {
                // Because of race inside tethering module, there is no guarantee wifi tethering
                // would restart successfully. If tethering don't auto restarted, restarting it
                // manually. TODO(b/242649651): remove this when tethering auto restart is reliable.
                newIface = mCtsTetheringUtils.startWifiTethering(tetherEventCallback);
            }

            nif = NetworkInterface.getByName(newIface.getInterface());
            final LinkAddress newHotspotAddr = getFirstIpv4Address(nif);
            assertNotNull(newHotspotAddr);

            assertFalse(testPrefix.containsPrefix(
                    new IpPrefix(newHotspotAddr.getAddress(), newHotspotAddr.getPrefixLength())));

            mCtsTetheringUtils.stopWifiTethering(tetherEventCallback);
        } finally {
            teardown(tnt);
            mCtsTetheringUtils.stopAllTethering();
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
        }
    }

    private LinkAddress getFirstIpv4Address(final NetworkInterface nif) {
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
            final LinkAddress addr = new LinkAddress(ia.getAddress(), ia.getNetworkPrefixLength());
            if (addr.isIpv4()) return addr;
        }
        return null;
    }

    @NonNull
    private IpPrefix getConflictingPrefix(final LinkAddress address,
            final boolean wholeRangeConflict) {
        if (!wholeRangeConflict) {
            return new IpPrefix(address.getAddress(), address.getPrefixLength());
        }

        final ArrayList<IpPrefix> prefixPool = new ArrayList<>(Arrays.asList(
                new IpPrefix("192.168.0.0/16"),
                new IpPrefix("172.16.0.0/12"),
                new IpPrefix("10.0.0.0/8")));

        for (IpPrefix prefix : prefixPool) {
            if (prefix.contains(address.getAddress())) return prefix;
        }

        fail("Could not find sutiable conflict prefix");

        // Never go here.
        return null;
    }

    private TestNetworkTracker setUpTestNetwork(final LinkAddress address) throws Exception {
        return runAsShell(MANAGE_TEST_NETWORKS, WRITE_SETTINGS,
                () -> initTestNetwork(mContext, address, 10_000L /* test timeout ms*/));

    }

    private void teardown(TestNetworkTracker tracker) throws Exception {
        if (tracker == null) return;

        runAsShell(MANAGE_TEST_NETWORKS, () -> tracker.teardown());
    }

    public static boolean isFeatureEnabled(final String name, final boolean defaultValue) {
        return runAsShell(READ_DEVICE_CONFIG,
                () -> DeviceConfig.getBoolean(NAMESPACE_CONNECTIVITY, name, defaultValue));
    }

    @Test
    public void testSilentEntitlementCheck() throws Exception {
        final TetheringManager tm = mContext.getSystemService(TetheringManager.class);
        final TetheringConfigurationParcel config = new TetheringConfigurationParcel();
        config.provisioningApp = new String[]{ENTITLEMENT_TEST_ACTION, ""};
        config.provisioningAppNoUi = ENTITLEMENT_TEST_ACTION;
        config.provisioningResponse = ENTITLEMENT_TEST_RESPONSE_ACTION;
        config.legacyDhcpRanges = new String[0];
        config.tetherableBluetoothRegexs = new String[0];
        config.tetherableUsbRegexs = new String[0];
        config.tetherableWifiRegexs = new String[] {"wlan0"};

        runAsShell(NETWORK_SETTINGS, () -> tm.setPreferTestNetworks(true));
        runAsShell(NETWORK_SETTINGS, () -> tm.setTetheringConfigurationForTest(config));

        final TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        TestNetworkTracker tnt = null;
        try {
            tetherEventCallback.assumeWifiTetheringSupported(mContext);
            tetherEventCallback.expectNoTetheringActive();
            tnt = setUpTestNetwork(
                    new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.1"), 25));
            mCtsTetheringUtils.startWifiTethering(tetherEventCallback);
            assertNotNull(tetherEventCallback.getCurrentValidUpstream());
            mEntitlementResponder.expectTestAction();
            mCtsTetheringUtils.stopWifiTethering(tetherEventCallback);
        } finally {
            mCtsTetheringUtils.stopAllTethering();
            mCtsTetheringUtils.unregisterTetheringEventCallback(tetherEventCallback);
            runAsShell(NETWORK_SETTINGS, () -> tm.setTetheringConfigurationForTest(null));
            runAsShell(NETWORK_SETTINGS, () -> tm.setPreferTestNetworks(false));
            teardown(tnt);
        }
    }

    private final TestBroadcastReceiver mEntitlementResponder = new TestBroadcastReceiver();
    class TestBroadcastReceiver extends BroadcastReceiver {
        private static final int TIMEOUT_MS = 30_000;
        private final ArrayTrackRecord<Intent>.ReadHead mHistory =
                new ArrayTrackRecord<Intent>().newReadHead();

        @Override
        public void onReceive(Context context, Intent intent) {
            mHistory.add(intent);
            if (!intent.getAction().equals(ENTITLEMENT_TEST_ACTION)) {
                Log.e(TAG, "Received unexpected action=" + intent.getAction());
                return;
            }
            final Intent response = new Intent(ENTITLEMENT_TEST_RESPONSE_ACTION);
            response.putExtra(EXTRA_RESULT, Activity.RESULT_OK);
            mContext.sendBroadcast(response);
        }

        public void expectTestAction() {
            assertNotNull(mHistory.poll(TIMEOUT_MS, (intent) ->
                    intent.getAction().equals(ENTITLEMENT_TEST_ACTION)));
        }
    }
}
