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

package android.net.cts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.net.IpSecTransformState;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.BitSet;

@RunWith(AndroidJUnit4.class)
public class IpSecTransformStateTest {
    private static final int REPLAY_BITMAP_LEN_BYTE = 512;

    @Test
    public void testBuild() throws Exception {
        final long timestamp = System.currentTimeMillis();

        // Arbitrary numbers
        final long txSequenceNumber = 1000L;
        final long rxSequenceNumber = 2000L;
        final long packetCount = 3000L;
        final long byteCount = 100000L;
        final byte[] replayBitmap = newReplayBitmap(2000);

        final IpSecTransformState transformState =
                new IpSecTransformState.Builder()
                        .setTimestampMillis(timestamp)
                        .setTxHighestSequenceNumber(txSequenceNumber)
                        .setRxHighestSequenceNumber(rxSequenceNumber)
                        .setPacketCount(packetCount)
                        .setByteCount(byteCount)
                        .setReplayBitmap(replayBitmap)
                        .build();

        assertEquals(timestamp, transformState.getTimestampMillis());
        assertEquals(txSequenceNumber, transformState.getTxHighestSequenceNumber());
        assertEquals(rxSequenceNumber, transformState.getRxHighestSequenceNumber());
        assertEquals(packetCount, transformState.getPacketCount());
        assertArrayEquals(replayBitmap, transformState.getReplayBitmap());
    }

    private static byte[] newReplayBitmap(int receivedPktCnt) {
        final BitSet bitSet = new BitSet(REPLAY_BITMAP_LEN_BYTE * 8);
        for (int i = 0; i < receivedPktCnt; i++) {
            bitSet.set(i);
        }
        return Arrays.copyOf(bitSet.toByteArray(), REPLAY_BITMAP_LEN_BYTE);
    }
}
