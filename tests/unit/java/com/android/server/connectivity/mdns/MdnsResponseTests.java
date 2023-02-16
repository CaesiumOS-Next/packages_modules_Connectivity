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

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.net.Network;

import com.android.net.module.util.HexDump;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.List;

// The record test data does not use compressed names (label pointers), since that would require
// additional data to populate the label dictionary accordingly.
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsResponseTests {
    private static final String TAG = "MdnsResponseTests";
    // MDNS response packet for name "test" with an IPv4 address of 10.1.2.3
    private static final byte[] DATAIN_IPV4 = HexDump.hexStringToByteArray(
            "0474657374000001" + "0001000011940004" + "0A010203");
    // MDNS response w/name "test" & IPv6 address of aabb:ccdd:1122:3344:a0b0:c0d0:1020:3040
    private static final byte[] DATAIN_IPV6 = HexDump.hexStringToByteArray(
            "047465737400001C" + "0001000011940010" + "AABBCCDD11223344" + "A0B0C0D010203040");
    // MDNS response w/name "test" & PTR to foo.bar.quxx
    private static final byte[] DATAIN_PTR = HexDump.hexStringToByteArray(
            "047465737400000C" + "000100001194000E" + "03666F6F03626172" + "047175787800");
    // MDNS response w/name "test" & Service for host foo.bar.quxx
    private static final byte[] DATAIN_SERVICE = HexDump.hexStringToByteArray(
            "0474657374000021"
            + "0001000011940014"
            + "000100FF1F480366"
            + "6F6F036261720471"
            + "75787800");
    // MDNS response w/name "test" & the following text strings:
    // "a=hello there", "b=1234567890", and "xyz=!$$$"
    private static final byte[] DATAIN_TEXT = HexDump.hexStringToByteArray(
            "0474657374000010"
            + "0001000011940024"
            + "0D613D68656C6C6F"
            + "2074686572650C62"
            + "3D31323334353637"
            + "3839300878797A3D"
            + "21242424");

    private static final int INTERFACE_INDEX = 999;
    private final Network mNetwork = mock(Network.class);

    // The following helper classes act as wrappers so that IPv4 and IPv6 address records can
    // be explicitly created by type using same constructor signature as all other records.
    static class MdnsInet4AddressRecord extends MdnsInetAddressRecord {
        public MdnsInet4AddressRecord(String[] name, MdnsPacketReader reader) throws IOException {
            super(name, MdnsRecord.TYPE_A, reader);
        }
    }

    static class MdnsInet6AddressRecord extends MdnsInetAddressRecord {
        public MdnsInet6AddressRecord(String[] name, MdnsPacketReader reader) throws IOException {
            super(name, MdnsRecord.TYPE_AAAA, reader);
        }
    }

    @Test
    public void getInet4AddressRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(DATAIN_IPV4, DATAIN_IPV4.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsInetAddressRecord record = new MdnsInetAddressRecord(name, MdnsRecord.TYPE_A, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasInet4AddressRecord());
        assertTrue(response.setInet4AddressRecord(record));
        assertEquals(response.getInet4AddressRecord(), record);
    }

    @Test
    public void getInet6AddressRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(DATAIN_IPV6, DATAIN_IPV6.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsInetAddressRecord record =
                new MdnsInetAddressRecord(name, MdnsRecord.TYPE_AAAA, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasInet6AddressRecord());
        assertTrue(response.setInet6AddressRecord(record));
        assertEquals(response.getInet6AddressRecord(), record);
    }

    @Test
    public void getPointerRecords_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(DATAIN_PTR, DATAIN_PTR.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsPointerRecord record = new MdnsPointerRecord(name, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasPointerRecords());
        assertTrue(response.addPointerRecord(record));
        List<MdnsPointerRecord> recordList = response.getPointerRecords();
        assertNotNull(recordList);
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));
    }

    @Test
    public void getServiceRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(DATAIN_SERVICE, DATAIN_SERVICE.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsServiceRecord record = new MdnsServiceRecord(name, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasServiceRecord());
        assertTrue(response.setServiceRecord(record));
        assertEquals(response.getServiceRecord(), record);
    }

    @Test
    public void getTextRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(DATAIN_TEXT, DATAIN_TEXT.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsTextRecord record = new MdnsTextRecord(name, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasTextRecord());
        assertTrue(response.setTextRecord(record));
        assertEquals(response.getTextRecord(), record);
    }

    @Test
    public void getInterfaceIndex() {
        final MdnsResponse response1 = new MdnsResponse(/* now= */ 0, INTERFACE_INDEX, mNetwork);
        assertEquals(INTERFACE_INDEX, response1.getInterfaceIndex());

        final MdnsResponse response2 =
                new MdnsResponse(/* now= */ 0, 1234 /* interfaceIndex */, mNetwork);
        assertEquals(1234, response2.getInterfaceIndex());
    }

    @Test
    public void testGetNetwork() {
        final MdnsResponse response1 =
                new MdnsResponse(/* now= */ 0, INTERFACE_INDEX, null /* network */);
        assertNull(response1.getNetwork());

        final MdnsResponse response2 =
                new MdnsResponse(/* now= */ 0, 1234 /* interfaceIndex */, mNetwork);
        assertEquals(mNetwork, response2.getNetwork());
    }
}