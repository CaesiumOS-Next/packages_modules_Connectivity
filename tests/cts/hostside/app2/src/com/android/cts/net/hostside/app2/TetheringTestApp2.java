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

package com.android.cts.net.hostside.app2;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.net.TetheringInterface;
import android.net.cts.util.CtsTetheringUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TetheringTestApp2  {
    private CtsTetheringUtils mCtsTetheringUtils;

    @Before
    public void setUp() throws Exception {
        mCtsTetheringUtils = new CtsTetheringUtils(getInstrumentation().getTargetContext());
    }

    /**
     * Tests that a tethered wifi interface started by a different app will have its
     * SoftApConfiguration redacted for this app. This test requires that
     * com.android.cts.net.hostside.TetheringTest#testStartWifiTethering was called first.
     */
    @Test
    public void testSoftApConfigurationRedactedFromTetheringInterface() {
        final CtsTetheringUtils.TestTetheringEventCallback tetherEventCallback =
                mCtsTetheringUtils.registerTetheringEventCallback();

        final TetheringInterface tetheredIface =
                tetherEventCallback.expectTetheredWifiInterfacesChanged();
        assertNotNull(tetheredIface);
        assertNull(tetheredIface.getSoftApConfiguration());
    }
}
