/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.remoteauth.jni;

import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit test for {@link com.android.server.remoteauth.jni.NativeRemoteAuthService} */
public class NativeRemoteAuthServiceTest {
    @Mock private INativeRemoteAuthService.IPlatform mPlatform;
    private NativeRemoteAuthService mService;
    private static final int CONNECTION_ID = 1;
    private static final byte[] REQUEST = new byte[] {(byte) 0x01 };
    private static final int RESPONSE_HANDLE = 2;
    private static final int PLATFORM_HANDLE = 3;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mService = new NativeRemoteAuthService();
        mService.setDeviceListener(mPlatform);
    }

    @Test
    public void testSendRequest_Success() {
        mService.sendRequest(CONNECTION_ID, REQUEST, RESPONSE_HANDLE, PLATFORM_HANDLE);
        verify(mPlatform, times(1)).sendRequest(CONNECTION_ID, REQUEST, anyObject());
    }
}
