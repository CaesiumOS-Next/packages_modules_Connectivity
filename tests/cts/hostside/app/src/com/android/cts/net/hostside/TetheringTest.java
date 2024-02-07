/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.net.TetheringInterface;
import android.net.cts.util.CtsTetheringUtils;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiSsid;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class TetheringTest {
    private CtsTetheringUtils mCtsTetheringUtils;


    @Before
    public void setUp() throws Exception {
        mCtsTetheringUtils = new CtsTetheringUtils(getInstrumentation().getTargetContext());
    }

    /**
     * Tests that Wifi tethering can be started with a SoftApConfiguration, and leaves up the
     * tethered interface so that com.android.cts.net.hostside.app2.TetheringTestApp2 can verify
     * SoftApConfiguration redaction for different uids. The tethered interface should be removed
     * with {@link #testStopTethering()}
     * @throws Exception
     */
    @Test
    public void testStartWifiTethering() throws Exception {
        final CtsTetheringUtils.TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();

        SoftApConfiguration softApConfig = new SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes("This is an SSID!"
                        .getBytes(StandardCharsets.UTF_8))).build();
        final TetheringInterface tetheredIface =
                mCtsTetheringUtils.startWifiTethering(tetherEventCallback, softApConfig);
        assertNotNull(tetheredIface);
        assertEquals(softApConfig, tetheredIface.getSoftApConfiguration());
    }

    /**
     * Stops tethering. Should be called after {@link #testStartWifiTethering()}, which does not
     * tear down the tethered interface itself.
     */
    @Test
    public void testStopTethering() {
        mCtsTetheringUtils.stopAllTethering();
    }
}
