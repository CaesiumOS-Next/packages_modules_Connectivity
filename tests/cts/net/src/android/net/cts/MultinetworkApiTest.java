/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkUtils;
import android.net.cts.util.CtsNetUtils;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.platform.test.annotations.AppModeFull;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MultinetworkApiTest extends AndroidTestCase {

    static {
        System.loadLibrary("nativemultinetwork_jni");
    }

    private static final String TAG = "MultinetworkNativeApiTest";
    static final String GOOGLE_PRIVATE_DNS_SERVER = "dns.google";

    private static final long TCPDUMP_START_DELAY_MS = 1000L;

    /**
     * @return 0 on success
     */
    private static native int runGetaddrinfoCheck(long networkHandle);
    private static native int runSetprocnetwork(long networkHandle);
    private static native int runSetsocknetwork(long networkHandle);
    private static native int runDatagramCheck(long networkHandle);
    private static native void runResNapiMalformedCheck(long networkHandle);
    private static native void runResNcancelCheck(long networkHandle);
    private static native void runResNqueryCheck(long networkHandle);
    private static native void runResNsendCheck(long networkHandle);
    private static native void runResNnxDomainCheck(long networkHandle);


    private ContentResolver mCR;
    private ConnectivityManager mCM;
    private CtsNetUtils mCtsNetUtils;
    private String mOldMode;
    private String mOldDnsSpecifier;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mCM = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        mCR = getContext().getContentResolver();
        mCtsNetUtils = new CtsNetUtils(getContext());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testGetaddrinfo() throws ErrnoException {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            int errno = runGetaddrinfoCheck(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "getaddrinfo on " + mCM.getNetworkInfo(network), -errno);
            }
        }
    }

    @AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
    public void testSetprocnetwork() throws ErrnoException {
        // Hopefully no prior test in this process space has set a default network.
        assertNull(mCM.getProcessDefaultNetwork());
        assertEquals(0, NetworkUtils.getBoundNetworkForProcess());

        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            mCM.setProcessDefaultNetwork(null);
            assertNull(mCM.getProcessDefaultNetwork());

            int errno = runSetprocnetwork(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "setprocnetwork on " + mCM.getNetworkInfo(network), -errno);
            }
            Network processDefault = mCM.getProcessDefaultNetwork();
            assertNotNull(processDefault);
            assertEquals(network, processDefault);
            // TODO: open DatagramSockets, connect them to 192.0.2.1 and 2001:db8::,
            // and ensure that the source address is in fact on this network as
            // determined by mCM.getLinkProperties(network).

            mCM.setProcessDefaultNetwork(null);
        }

        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            NetworkUtils.bindProcessToNetwork(0);
            assertNull(mCM.getBoundNetworkForProcess());

            int errno = runSetprocnetwork(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "setprocnetwork on " + mCM.getNetworkInfo(network), -errno);
            }
            assertEquals(network, new Network(mCM.getBoundNetworkForProcess()));
            // TODO: open DatagramSockets, connect them to 192.0.2.1 and 2001:db8::,
            // and ensure that the source address is in fact on this network as
            // determined by mCM.getLinkProperties(network).

            NetworkUtils.bindProcessToNetwork(0);
        }
    }

    @AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
    public void testSetsocknetwork() throws ErrnoException {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            int errno = runSetsocknetwork(network.getNetworkHandle());
            if (errno != 0) {
                throw new ErrnoException(
                        "setsocknetwork on " + mCM.getNetworkInfo(network), -errno);
            }
        }
    }

    public void testNativeDatagramTransmission() throws ErrnoException {
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            int errno = runDatagramCheck(network.getNetworkHandle()) + 1;
            if (errno != 0) {
                if (Build.isDebuggable()) {
                    Log.i(TAG, "Test failed; re-running to get tcpdump");
                    try {
                        tryNativeDatagramTransmissionWithDump(network);
                    } catch (Exception e) {
                        Log.e(TAG, "Error re-running test to get tcpdump", e);
                    }
                }

                throw new ErrnoException(
                        "DatagramCheck on " + mCM.getNetworkInfo(network), -errno);
            }
        }
    }

    private void tryNativeDatagramTransmissionWithDump(Network network) throws Exception {
        final LinkProperties lp = mCM.getLinkProperties(network);
        if (lp == null) {
            Log.e(TAG, "Network is gone: " + network);
            return;
        }
        final UiAutomation automation = getInstrumentation().getUiAutomation();
        // executeShellCommand won't tokenize quoted arguments containing spaces properly, so pass
        // in the command in stdin instead of using sh -c 'command'
        final ParcelFileDescriptor[] fds = automation.executeShellCommandRw("sh");

        final ParcelFileDescriptor out = fds[0];
        final ParcelFileDescriptor in = fds[1];
        try (AutoCloseOutputStream writer = new AutoCloseOutputStream(in)) {
            writer.write(("echo $$; exec su 0 tcpdump -xx -n -i " + lp.getInterfaceName()
                    + " udp port 443 or udp port 53 or icmp or icmp6 or arp")
                    .getBytes(StandardCharsets.UTF_8));
        }
        // There is no good way to get notified that tcpdump is listening, or to reliably poll for
        // an event, so a hardcoded delay is used here. This should generally be avoided, but is not
        // problematic here as tcpdump is only for diagnostics purposes in this attempt.
        Thread.sleep(TCPDUMP_START_DELAY_MS);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new AutoCloseInputStream(out)))) {
            final int pid = Integer.parseInt(reader.readLine());
            Log.i(TAG, "Running tcpdump as pid " + pid);

            final int errno = runDatagramCheck(network.getNetworkHandle());
            Log.i(TAG, "Test run finished with status " + (-errno) + ". tcpdump:");

            // Similarly to the delay before starting the test, tcpdump needs to get enough time
            // to capture/output the packets. Hardcoded delays should generally be avoided, but
            // is not problematic here as tcpdump is only for diagnostics purposes in this attempt.
            Thread.sleep(TCPDUMP_START_DELAY_MS);
            try (AutoCloseInputStream killReader = new AutoCloseInputStream(
                    automation.executeShellCommand("su 0 kill " + pid))) {
                // Read stdout to the end to ensure the command finishes
                killReader.readAllBytes();
            }
            reader.lines().forEach(l -> Log.i(TAG, l));
        }
    }

    public void testNoSuchNetwork() {
        final Network eNoNet = new Network(54321);
        assertNull(mCM.getNetworkInfo(eNoNet));

        final long eNoNetHandle = eNoNet.getNetworkHandle();
        assertEquals(-OsConstants.ENONET, runSetsocknetwork(eNoNetHandle));
        assertEquals(-OsConstants.ENONET, runSetprocnetwork(eNoNetHandle));
        // TODO: correct test permissions so this call is not silently re-mapped
        // to query on the default network.
        // assertEquals(-OsConstants.ENONET, runGetaddrinfoCheck(eNoNetHandle));
    }

    public void testNetworkHandle() {
        // Test Network -> NetworkHandle -> Network results in the same Network.
        for (Network network : mCtsNetUtils.getTestableNetworks()) {
            long networkHandle = network.getNetworkHandle();
            Network newNetwork = Network.fromNetworkHandle(networkHandle);
            assertEquals(newNetwork, network);
        }

        // Test that only obfuscated handles are allowed.
        try {
            Network.fromNetworkHandle(100);
            fail();
        } catch (IllegalArgumentException e) {}
        try {
            Network.fromNetworkHandle(-1);
            fail();
        } catch (IllegalArgumentException e) {}
        try {
            Network.fromNetworkHandle(0);
            fail();
        } catch (IllegalArgumentException e) {}
    }

    public void testResNApi() throws Exception {
        final Network[] testNetworks = mCtsNetUtils.getTestableNetworks();

        for (Network network : testNetworks) {
            // Throws AssertionError directly in jni function if test fail.
            runResNqueryCheck(network.getNetworkHandle());
            runResNsendCheck(network.getNetworkHandle());
            runResNcancelCheck(network.getNetworkHandle());
            runResNapiMalformedCheck(network.getNetworkHandle());

            final NetworkCapabilities nc = mCM.getNetworkCapabilities(network);
            // Some cellular networks configure their DNS servers never to return NXDOMAIN, so don't
            // test NXDOMAIN on these DNS servers.
            // b/144521720
            if (nc != null && !nc.hasTransport(TRANSPORT_CELLULAR)) {
                runResNnxDomainCheck(network.getNetworkHandle());
            }
        }
    }

    @AppModeFull(reason = "WRITE_SECURE_SETTINGS permission can't be granted to instant apps")
    public void testResNApiNXDomainPrivateDns() throws InterruptedException {
        mCtsNetUtils.storePrivateDnsSetting();
        // Enable private DNS strict mode and set server to dns.google before doing NxDomain test.
        // b/144521720
        try {
            mCtsNetUtils.setPrivateDnsStrictMode(GOOGLE_PRIVATE_DNS_SERVER);
            for (Network network : mCtsNetUtils.getTestableNetworks()) {
              // Wait for private DNS setting to propagate.
              mCtsNetUtils.awaitPrivateDnsSetting("NxDomain test wait private DNS setting timeout",
                        network, GOOGLE_PRIVATE_DNS_SERVER, true);
              runResNnxDomainCheck(network.getNetworkHandle());
            }
        } finally {
            mCtsNetUtils.restorePrivateDnsSetting();
        }
    }
}
