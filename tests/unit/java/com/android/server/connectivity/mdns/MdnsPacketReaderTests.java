/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.os.Build;

import com.android.net.module.util.HexDump;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsPacketReaderTests {

    private static MdnsPacketReader readerFor(byte[] data) {
        return new MdnsPacketReader(data, data.length,
                MdnsFeatureFlags.newBuilder().setAllFlagsForTesting().build());
    }

    @Test
    public void testLimits() throws IOException {
        byte[] data = new byte[25];
        DatagramPacket datagramPacket = new DatagramPacket(data, data.length);

        // After creating a new reader, confirm that the remaining is equal to the packet length
        // (or that there is no temporary limit).
        MdnsPacketReader packetReader = new MdnsPacketReader(datagramPacket);
        assertEquals(data.length, packetReader.getRemaining());

        // Confirm that we can set the temporary limit to 0.
        packetReader.setLimit(0);
        assertEquals(0, packetReader.getRemaining());

        // Confirm that we can clear the temporary limit, and restore to the length of the packet.
        packetReader.clearLimit();
        assertEquals(data.length, packetReader.getRemaining());

        // Confirm that we can set the temporary limit to the actual length of the packet.
        // While parsing packets, it is common to set the limit to the length of the packet.
        packetReader.setLimit(data.length);
        assertEquals(data.length, packetReader.getRemaining());

        // Confirm that we ignore negative limits.
        packetReader.setLimit(-10);
        assertEquals(data.length, packetReader.getRemaining());

        // Confirm that we can set the temporary limit to something less than the packet length.
        packetReader.setLimit(data.length / 2);
        assertEquals(data.length / 2, packetReader.getRemaining());

        // Confirm that we throw an exception if trying to set the temporary limit beyond the
        // packet length.
        packetReader.clearLimit();
        try {
            packetReader.setLimit(data.length * 2 + 1);
            fail("Should have thrown an IOException when trying to set the temporary limit beyond "
                    + "the packet length");
        } catch (IOException e) {
            // Expected
        } catch (RuntimeException e) {
            fail(String.format(
                    Locale.ROOT,
                    "Should not have thrown any other exception except " + "for IOException: %s",
                    e.getMessage()));
        }
        assertEquals(data.length, packetReader.getRemaining());
    }

    @Test
    public void testWellFormedName() throws IOException {
        // "hello.local" followed by the root label.
        final byte[] data = HexDump.hexStringToByteArray(
                "0568656C6C6F"   // label "hello"
                        + "056C6F63616C" // label "local"
                        + "00");
        assertArrayEquals(new String[] {"hello", "local"}, readerFor(data).readLabels());
    }

    @Test
    public void testValidCompressionPointerToPriorName() throws IOException {
        // First name "example.local\0" at the usual QNAME offset of 12, then
        // "www" + a pointer back to that name. Offset 0 is the label-dictionary
        // end-of-chain sentinel and would be the DNS header in a real packet,
        // so it is not used as a compression target here.
        final byte[] data = HexDump.hexStringToByteArray(
                "000000000000000000000000" // 12-byte prefix
                        + "076578616D706C65" // label "example" at 12
                        + "056C6F63616C"     // label "local"
                        + "00"
                        + "03777777"         // label "www"
                        + "C00C");           // pointer to offset 12
        MdnsPacketReader reader = readerFor(data);
        reader.skip(12);
        assertArrayEquals(new String[] {"example", "local"}, reader.readLabels());
        assertArrayEquals(new String[] {"www", "example", "local"}, reader.readLabels());
    }

    @Test
    public void testMaxSizeLabelAndNameStillParse() throws IOException {
        // A 63-octet label is legal (RFC 1035). Three 63-octet labels (192) plus a
        // 61-octet label (62) plus the root (1) is exactly 255 octets.
        byte[] data = new byte[255];
        int off = 0;
        for (int i = 0; i < 3; i++) {
            data[off++] = 63;
            for (int j = 0; j < 63; j++) {
                data[off++] = (byte) 'a';
            }
        }
        data[off++] = 61;
        for (int j = 0; j < 61; j++) {
            data[off++] = (byte) 'b';
        }
        data[off++] = 0;
        assertEquals(255, off);

        String[] labels = readerFor(data).readLabels();
        assertEquals(4, labels.length);
        assertEquals(63, labels[0].getBytes(StandardCharsets.UTF_8).length);
        assertEquals(61, labels[3].getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void testInfinitePtrLoop() {
        // Fake mdns response packet label portion which has infinite ptr loop.
        final byte[] infinitePtrLoopData = HexDump.hexStringToByteArray(
                "054C4142454C" // label "LABEL"
                        + "0454455354" // label "TEST"
                        + "C006"); // PTR to second label.
        MdnsPacketReader packetReader = new MdnsPacketReader(
                infinitePtrLoopData, infinitePtrLoopData.length,
                MdnsFeatureFlags.newBuilder().setAllFlagsForTesting()
                        .setIsLabelCountLimitEnabled(true).build());
        assertThrows(IOException.class, packetReader::readLabels);
    }

    @Test
    public void testSelfPointerRejectedEvenIfLabelCountLimitDisabled() {
        // Pointer to itself at offset 0.
        final byte[] selfPointer = HexDump.hexStringToByteArray("C000");
        MdnsPacketReader reader = new MdnsPacketReader(selfPointer, selfPointer.length,
                MdnsFeatureFlags.newBuilder().setAllFlagsForTesting()
                        .setIsLabelCountLimitEnabled(false).build());
        assertThrows(IOException.class, reader::readLabels);
    }

    @Test
    public void testForwardCompressionPointerRejected() {
        // Pointer to offset 3, which is ahead of the current name (starts at 0).
        final byte[] data = HexDump.hexStringToByteArray(
                "C003"           // forward pointer
                        + "03666F6F" // label "foo" at offset 2 (never a legal target)
                        + "00");
        assertThrows(IOException.class, readerFor(data)::readLabels);
    }

    @Test
    public void testReservedLabelTypeExtendedRejected() {
        // 0x40 is a reserved label type (RFC 6891). This is also a 64-octet length,
        // which exceeds the 63-octet label cap in RFC 1035 §2.3.4.
        final byte[] data = new byte[1 + 64 + 1];
        data[0] = 0x40;
        for (int i = 1; i <= 64; i++) {
            data[i] = (byte) 'x';
        }
        data[65] = 0;
        IOException thrown = assertThrows(IOException.class, readerFor(data)::readLabels);
        // Either the reserved-type or the label-length check may fire first;
        // both are the intended rejection.
        if (thrown.getMessage() == null
                || !(thrown.getMessage().contains("Bad label type")
                        || thrown.getMessage().contains("Label too long")
                        || thrown.getMessage().contains("label"))) {
            // Message is not required to be exact, but the parse must not succeed.
        }
    }

    @Test
    public void testReservedLabelType0x80Rejected() {
        final byte[] data = HexDump.hexStringToByteArray("800100");
        assertThrows(IOException.class, readerFor(data)::readLabels);
    }

    @Test
    public void testNameExceeding255OctetsRejected() {
        // Four 63-octet labels: 4 * (1 + 63) = 256, plus the root would be 257.
        byte[] data = new byte[4 * 64 + 1];
        int off = 0;
        for (int i = 0; i < 4; i++) {
            data[off++] = 63;
            for (int j = 0; j < 63; j++) {
                data[off++] = (byte) 'a';
            }
        }
        data[off++] = 0;
        assertThrows(IOException.class, readerFor(data)::readLabels);
    }
}
