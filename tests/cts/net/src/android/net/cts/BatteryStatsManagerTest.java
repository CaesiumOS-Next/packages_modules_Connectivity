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

import static android.Manifest.permission.UPDATE_DEVICE_STATS;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.testutils.MiscAsserts.assertThrows;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.cts.util.CtsNetUtils;
import android.os.BatteryStatsManager;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.WifiBatteryStats;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Test for BatteryStatsManager.
 */
public class BatteryStatsManagerTest{
    private static final String TAG = BatteryStatsManagerTest.class.getSimpleName();
    private static final String TEST_URL = "https://connectivitycheck.gstatic.com/generate_204";
    // This value should be the same as BatteryStatsManager.BATTERY_STATUS_DISCHARGING.
    private static final int BATTERY_STATUS_DISCHARGING = 3;

    private Context mContext;
    private BatteryStatsManager mBsm;
    private ConnectivityManager mCm;
    private CtsNetUtils mCtsNetUtils;

    @Before
    public void setUp() throws Exception {
        mContext = getContext();
        mBsm = mContext.getSystemService(BatteryStatsManager.class);
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mCtsNetUtils = new CtsNetUtils(mContext);
    }

    @Test
    public void testReportNetworkInterfaceForTransports() throws Exception {
        try {
            final Network cellNetwork = mCtsNetUtils.connectToCell();
            final URL url = new URL(TEST_URL);

            // Simulate the device being unplugged from charging.
            executeShellCommand("dumpsys battery unplug");
            executeShellCommand("dumpsys battery set status " + BATTERY_STATUS_DISCHARGING);
            executeShellCommand("dumpsys batterystats enable pretend-screen-off");

            // Get cellular battery stats
            CellularBatteryStats cellularBatteryStatsBefore = runAsShell(UPDATE_DEVICE_STATS,
                    mBsm::getCellularBatteryStats);
            final long cellNumByteTxBefore = cellularBatteryStatsBefore.getNumBytesTx();
            final long cellNumByteRxBefore = cellularBatteryStatsBefore.getNumBytesRx();
            final long cellNumPacketsTxBefore = cellularBatteryStatsBefore.getNumPacketsTx();
            final long cellNumPacketsRxBefore = cellularBatteryStatsBefore.getNumPacketsRx();

            // Generate traffic on cellular network.
            generateNetworkTraffic(cellNetwork, url);

            mCtsNetUtils.toggleWifi();
            // Wait for updating mobile stats
            Thread.sleep(200);
            final CellularBatteryStats cellularBatteryStatsAfter = runAsShell(UPDATE_DEVICE_STATS,
                    mBsm::getCellularBatteryStats);

            long cellNumByteTxAfter = cellularBatteryStatsAfter.getNumBytesTx();
            long cellNumByteRxAfter = cellularBatteryStatsAfter.getNumBytesRx();
            long cellNumPacketsTxAfter = cellularBatteryStatsAfter.getNumPacketsTx();
            long cellNumPacketsRxAfter = cellularBatteryStatsAfter.getNumPacketsRx();

            assertGreaterThan(cellNumByteTxAfter, cellNumByteTxBefore);
            assertGreaterThan(cellNumByteRxAfter, cellNumByteRxBefore);
            assertGreaterThan(cellNumPacketsTxAfter, cellNumPacketsTxBefore);
            assertGreaterThan(cellNumPacketsRxAfter, cellNumPacketsRxBefore);

            Network wifiNetwork = mCtsNetUtils.connectToWifi();
            WifiBatteryStats wifiBatteryStatsBefore = runAsShell(UPDATE_DEVICE_STATS,
                    mBsm::getWifiBatteryStats);

            final long wifiNumByteRxBefore = wifiBatteryStatsBefore.getNumBytesRx();
            final long wifiNumByteTxBefore = wifiBatteryStatsBefore.getNumBytesTx();
            final long wifiNumPacketsTxBefore = wifiBatteryStatsBefore.getNumPacketsTx();
            final long wifiNumPacketsRxBefore = wifiBatteryStatsBefore.getNumPacketsRx();

            // Generate traffic on wifi network.
            generateNetworkTraffic(wifiNetwork, url);

            mCtsNetUtils.toggleWifi();
            // Wait for updating wifi stats
            Thread.sleep(200);
            WifiBatteryStats wifiBatteryStatsAfter = runAsShell(UPDATE_DEVICE_STATS,
                    mBsm::getWifiBatteryStats);

            final long wifiNumByteRxAfter = wifiBatteryStatsAfter.getNumBytesRx();
            final long wifiNumByteTxAfter = wifiBatteryStatsAfter.getNumBytesTx();
            final long wifiNumPacketsTxAfter = wifiBatteryStatsAfter.getNumPacketsTx();
            final long wifiNumPacketsRxAfter = wifiBatteryStatsAfter.getNumPacketsRx();

            assertGreaterThan(wifiNumByteTxAfter, wifiNumByteTxBefore);
            assertGreaterThan(wifiNumByteRxAfter, wifiNumByteRxBefore);
            assertGreaterThan(wifiNumPacketsTxAfter, wifiNumPacketsTxBefore);
            assertGreaterThan(wifiNumPacketsRxAfter, wifiNumPacketsRxBefore);
        } finally {
            // Reset battery settings.
            executeShellCommand("dumpsys battery reset");
            executeShellCommand("dumpsys batterystats disable pretend-screen-off");
        }
    }

    @Test
    public void testReportNetworkInterfaceForTransports_throwsSecurityException()
            throws Exception {
        Network wifiNetwork = mCtsNetUtils.connectToWifi();
        final String iface = mCm.getLinkProperties(wifiNetwork).getInterfaceName();
        final int[] transportType = mCm.getNetworkCapabilities(wifiNetwork).getTransportTypes();
        assertThrows(SecurityException.class,
                () -> mBsm.reportNetworkInterfaceForTransports(iface, transportType));
    }

    private void generateNetworkTraffic(Network network, URL url) throws IOException {
        if (!mCm.bindProcessToNetwork(network)) {
            Log.e(TAG, "bindProcessToNetwork Failed!");
            throw new IllegalStateException("bindProcessToNetwork failed!");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) network.openConnection(url);
            connection.connect();
        } catch (IOException e) {
            Log.e(TAG, "Generate traffic failed with exception " + e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void assertGreaterThan(long left, long right) {
        assertTrue("<" + left + "> should be greater than <" + right + ">", left > right);
    }

    private static String executeShellCommand(String command) {
        final String result = runShellCommand(command).trim();
        Log.d(TAG, "Output of '" + command + "': '" + result + "'");
        return result;
    }
}


