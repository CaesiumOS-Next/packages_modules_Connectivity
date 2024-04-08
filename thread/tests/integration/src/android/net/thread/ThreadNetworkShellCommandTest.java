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

package android.net.thread;

import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;
import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.io.BaseEncoding.base16;
import static org.junit.Assert.assertThrows;
import android.net.InetAddresses;
import android.content.Context;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Integration tests for {@link ThreadNetworkShellCommand}. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadNetworkShellCommandTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private static final byte[] DEFAULT_DATASET_TLVS =
    base16().decode(
                    "0E080000000000010000000300001335060004001FFFE002"
                            + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                            + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                            + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                            + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_DATASET =
        ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private OtDaemonController mOtCtl;
    private FullThreadDevice mFtd;

    @Before
    public void setUp() {
        mOtCtl = new OtDaemonController();
        mFtd = new FullThreadDevice(10 /* nodeId */);
        ensureThreadEnabled();
        mOtCtl.factoryReset();
    }

    @After
    public void tearDown() {
        ensureThreadEnabled();
        mFtd.destroy();
    }

    private static void ensureThreadEnabled() {
        runThreadCommand("force-stop-ot-daemon disabled");
        runThreadCommand("enable");
    }

    @Test
    public void enable_threadStateIsEnabled() throws Exception {
        runThreadCommand("enable");

        assertThat(mController.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void disable_threadStateIsDisabled() throws Exception {
        runThreadCommand("disable");

        assertThat(mController.getEnabledState()).isEqualTo(STATE_DISABLED);
    }

    @Test
    public void forceStopOtDaemon_forceStopEnabled_otDaemonServiceDisappear() {
        runThreadCommand("force-stop-ot-daemon enabled");

        assertThat(runShellCommandOrThrow("service list")).doesNotContain("ot_daemon");
    }

    @Test
    public void forceStopOtDaemon_forceStopEnabled_canNotEnableThread() throws Exception {
        runThreadCommand("force-stop-ot-daemon enabled");

        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> mController.setEnabledAndWait(true));
        ThreadNetworkException cause = (ThreadNetworkException) thrown.getCause();
        assertThat(cause.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void forceStopOtDaemon_forceStopDisabled_otDaemonServiceAppears() throws Exception {
        runThreadCommand("force-stop-ot-daemon disabled");

        assertThat(runShellCommandOrThrow("service list")).contains("ot_daemon");
    }

    @Test
    public void forceStopOtDaemon_forceStopDisabled_canEnableThread() throws Exception {
        runThreadCommand("force-stop-ot-daemon disabled");

        mController.setEnabledAndWait(true);
        assertThat(mController.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void forceCountryCode_setCN_getCountryCodeReturnsCN() {
        runThreadCommand("force-country-code enabled CN");

        final String result = runThreadCommand("get-country-code");
        assertThat(result).contains("Thread country code = CN");
    }

    @Test
    public void startUdpEchoServer_canReplyToUdpPacket() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);
        startFtdChild(mFtd, DEFAULT_DATASET);
        final Inet6Address serverAddress = mOtCtl.getMeshLocalAddresses().get(0);
        final int serverPort = 9527;
        final Thread t = new Thread(() -> runShellCommand("cmd thread_network start-udp-echo-server " + serverAddress.getHostAddress() + " " + serverPort));

        t.start();
        Log.i("xxxxxxxxxx", "thread started");
        //mFtd.udpOpen();
        mFtd.udpBind((Inet6Address) InetAddresses.parseNumericAddress("::"), serverPort);
        mFtd.udpSend("Hello,Thread", serverAddress, serverPort);
        Log.i("xxxxxxxxxx", "sent udp");
        String udpReply = mFtd.udpReceive();
        Log.i("xxxxxxxxxx", "received udp");
        t.join(1000 /* timeout millis */);
        Log.i("xxxxxxxxxx", "thread stopped");

        assertThat(udpReply).isEqualTo("Hello,Thread");
    }

    private static String runThreadCommand(String cmd) {
        return runShellCommandOrThrow("cmd thread_network " + cmd);
    }

    private void startFtdChild(FullThreadDevice ftd, ActiveOperationalDataset activeDataset)
            throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(activeDataset);
        ftd.waitForStateAnyOf(List.of("router", "child"), Duration.ofSeconds(8));
    }
}
