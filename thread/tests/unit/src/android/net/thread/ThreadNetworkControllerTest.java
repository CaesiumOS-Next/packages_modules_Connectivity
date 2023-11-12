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

package android.net.thread;

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_CHILD;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import android.os.Binder;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.concurrent.atomic.AtomicInteger;

/** Unit tests for {@link ThreadNetworkController}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ThreadNetworkControllerTest {
    private static final int FAKE_UID = 99999;

    @Mock private IThreadNetworkController mMockService;
    private ThreadNetworkController mController;

    private int mUid;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mUid = Process.myUid();
        mController = new ThreadNetworkController(mMockService);
    }

    private static void setBinderUid(int uid) {
        // TODO: generally, it's not a good practice to depend on the implementation detail to set
        // a custom UID, but Connectivity, Wifi, UWB and etc modules are using this trick. Maybe
        // define a interface (e.b. CallerIdentityInjector) for easier mocking.
        Binder.restoreCallingIdentity((((long) uid) << 32) | Binder.getCallingPid());
    }

    private static IStateCallback getStateCallback(InvocationOnMock invocation) {
        return (IStateCallback) invocation.getArguments()[0];
    }

    @Test
    public void registerStateCallback_callbackIsInvokedWithCallingAppIdentity() throws Exception {
        setBinderUid(FAKE_UID);
        doAnswer(
                        invoke -> {
                            getStateCallback(invoke).onDeviceRoleChanged(DEVICE_ROLE_CHILD);
                            return null;
                        })
                .when(mMockService)
                .registerStateCallback(any(IStateCallback.class));

        AtomicInteger callbackUid = new AtomicInteger(0);
        mController.registerStateCallback(
                Runnable::run, state -> callbackUid.set(Binder.getCallingUid()));

        assertThat(callbackUid.get()).isNotEqualTo(FAKE_UID);
        assertThat(callbackUid.get()).isEqualTo(Process.myUid());
    }
}
