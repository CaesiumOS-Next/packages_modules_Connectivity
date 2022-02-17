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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;
import android.net.EthernetNetworkUpdateRequest;
import android.net.IpConfiguration;
import android.net.NetworkCapabilities;
import android.net.StaticIpConfiguration;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2) // TODO: Use to Build.VERSION_CODES.SC_V2 when available
@RunWith(DevSdkIgnoreRunner.class)
public class EthernetNetworkUpdateRequestTest {
    private static final NetworkCapabilities DEFAULT_CAPS =
            new NetworkCapabilities.Builder().build();
    private static final StaticIpConfiguration DEFAULT_STATIC_IP_CONFIG =
            new StaticIpConfiguration.Builder().build();
    private static final IpConfiguration DEFAULT_IP_CONFIG =
            new IpConfiguration.Builder()
                    .setStaticIpConfiguration(DEFAULT_STATIC_IP_CONFIG).build();

    private EthernetNetworkUpdateRequest createRequest(@NonNull final NetworkCapabilities nc,
            @NonNull final IpConfiguration ipConfig) {
        return new EthernetNetworkUpdateRequest.Builder()
                .setNetworkCapabilities(nc)
                .setIpConfiguration(ipConfig)
                .build();
    }

    @Test
    public void testGetNetworkCapabilities() {
        final NetworkCapabilities nc = new NetworkCapabilities.Builder()
                .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
                .build();

        final EthernetNetworkUpdateRequest r = createRequest(nc, DEFAULT_IP_CONFIG);

        assertEquals(nc, r.getNetworkCapabilities());
    }

    @Test
    public void testGetIpConfiguration() {
        final StaticIpConfiguration staticIpConfig = new StaticIpConfiguration.Builder()
                .setDomains("test")
                .build();
        final IpConfiguration ipConfig =
                new IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build();

        final EthernetNetworkUpdateRequest r = createRequest(DEFAULT_CAPS, ipConfig);

        assertEquals(ipConfig, r.getIpConfiguration());
    }

    @Test
    public void testNullNetworkCapabilitiesThrows() {
        assertThrows("Should not be able to build with null value.",
                NullPointerException.class,
                () -> createRequest(null, DEFAULT_IP_CONFIG));
    }

    @Test
    public void testNullIpConfigurationThrows() {
        assertThrows("Should not be able to build with null value.",
                NullPointerException.class,
                () -> createRequest(DEFAULT_CAPS, null));
    }
}
