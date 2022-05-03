/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.net.cts.util.CtsNetUtils.TestNetworkCallback;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.cts.util.CtsNetUtils;
import android.os.ParcelFileDescriptor;

import androidx.test.InstrumentationRegistry;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;

// Create this file to share TestNetworkWrapper with other CTS
public class TestNetworkWrapper implements AutoCloseable {
    private static final int IP4_PREFIX_LEN = 32;
    private static final int IP6_PREFIX_LEN = 64;

    private static final Context sContext = InstrumentationRegistry.getContext();
    private static final ConnectivityManager sCM =
            sContext.getSystemService(ConnectivityManager.class);
    private static final CtsNetUtils ctsNetUtils = new CtsNetUtils(sContext);
    private static TestNetworkManager sTNM;

    public final ParcelFileDescriptor tunFd;
    public final TestNetworkCallback tunNetworkCallback;
    public final Network tunNetwork;
    public final IkeTunUtils tunUtils;

    public TestNetworkWrapper(InetAddress... addresses) throws Exception {
        sTNM = sContext.getSystemService(TestNetworkManager.class);
        final LinkAddress[] linkAddresses = new LinkAddress[addresses.length];
        for (int i = 0; i < linkAddresses.length; i++) {
            InetAddress addr = addresses[i];
            if (addr instanceof Inet4Address) {
                linkAddresses[i] = new LinkAddress(addr, IP4_PREFIX_LEN);
            } else {
                linkAddresses[i] = new LinkAddress(addr, IP6_PREFIX_LEN);
            }
        }

        try {
            final TestNetworkInterface testIface = sTNM.createTunInterface(linkAddresses);

            tunFd = testIface.getFileDescriptor();

            tunNetworkCallback = ctsNetUtils.setupAndGetTestNetwork(testIface.getInterfaceName());
            tunNetwork = tunNetworkCallback.waitForAvailable();
        } catch (Exception e) {
            close();
            throw e;
        }

        tunUtils = new IkeTunUtils(tunFd);
    }

    public static TestNetworkWrapper createTestNetworkWrapper(InetAddress... addresses)
            throws Exception {
        TestNetworkWrapper testNetworkWrapper = new TestNetworkWrapper(addresses);
        assertNotNull("No test network found", testNetworkWrapper.tunNetwork);
        return testNetworkWrapper;
    }

    @Override
    public void close() throws IOException {
        if (tunNetworkCallback != null) {
            sCM.unregisterNetworkCallback(tunNetworkCallback);
        }

        if (tunNetwork != null) {
            sTNM.teardownTestNetwork(tunNetwork);
        }

        if (tunFd != null) {
            tunFd.close();
        }
    }
}
