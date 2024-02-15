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

package android.net.cts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.net.IpSecTransformState;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@RunWith(AndroidJUnit4.class)
public class IpSecTransformStateTest {
    @Rule public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final long TIMESTAMP_MILLIS = 1000L;
    private static final long HIGHEST_SEQ_NUMBER = 10000L;
    private static final long PACKET_COUNT = 9000L;
    private static final long BYTE_COUNT = 900000L;

    private static final int REPLAY_BITMAP_LEN_BYTE = 512;
    private static final byte[] REPLAY_BITMAP_NO_PACKETS = new byte[REPLAY_BITMAP_LEN_BYTE];
    private static final byte[] REPLAY_BITMAP_ALL_RECEIVED = new byte[REPLAY_BITMAP_LEN_BYTE];

    static {
        for (int i = 0; i < REPLAY_BITMAP_ALL_RECEIVED.length; i++) {
            REPLAY_BITMAP_ALL_RECEIVED[i] = (byte) 0xff;
        }
    }

    private void verifyBuild(boolean isTx, byte[] replayBitmap) {
        final long elapsedRealtimeBefore = SystemClock.elapsedRealtime();

        final IpSecTransformState.Builder stateBuilder =
                new IpSecTransformState.Builder()
                        .setPacketCount(PACKET_COUNT)
                        .setByteCount(BYTE_COUNT)
                        .setReplayBitmap(replayBitmap);
        if (isTx) {
            stateBuilder.setTxHighestSequenceNumber(HIGHEST_SEQ_NUMBER);
        } else {
            stateBuilder.setRxHighestSequenceNumber(HIGHEST_SEQ_NUMBER);
        }
        final IpSecTransformState state = stateBuilder.build();

        final long elapsedRealtimeAfter = SystemClock.elapsedRealtime();

        // Verify  elapsedRealtimeBefore <= state.getTimestampMillis() <= elapsedRealtimeAfter
        assertFalse(elapsedRealtimeBefore > state.getTimestampMillis());
        assertFalse(elapsedRealtimeAfter < state.getTimestampMillis());

        assertEquals(PACKET_COUNT, state.getPacketCount());
        assertEquals(BYTE_COUNT, state.getByteCount());
        assertArrayEquals(replayBitmap, state.getReplayBitmap());

        if (isTx) {
            assertEquals(HIGHEST_SEQ_NUMBER, state.getTxHighestSequenceNumber());
            assertEquals(0L, state.getRxHighestSequenceNumber());
        } else {
            assertEquals(0L, state.getTxHighestSequenceNumber());
            assertEquals(HIGHEST_SEQ_NUMBER, state.getRxHighestSequenceNumber());
        }
    }

    @Test
    public void testBuildForTx() {
        verifyBuild(true /* isTx */, REPLAY_BITMAP_NO_PACKETS);
    }

    @Test
    public void testBuildForRx() {
        verifyBuild(false /* isTx */, REPLAY_BITMAP_ALL_RECEIVED);
    }

    @Test
    public void testSetTimestampMillis() {
        final IpSecTransformState state =
                new IpSecTransformState.Builder()
                        .setTimestampMillis(TIMESTAMP_MILLIS)
                        .setReplayBitmap(REPLAY_BITMAP_NO_PACKETS)
                        .build();

        assertEquals(TIMESTAMP_MILLIS, state.getTimestampMillis());
    }

    @Test
    public void testBuildWithoutReplayBitmap() throws Exception {
        try {
            new IpSecTransformState.Builder().build();
            fail("Expected expcetion if replay bitmap is not set");
        } catch (NullPointerException expected) {
        }
    }
}
