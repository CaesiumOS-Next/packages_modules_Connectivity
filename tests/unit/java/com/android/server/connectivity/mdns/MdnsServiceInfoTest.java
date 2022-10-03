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

package com.android.server.connectivity.mdns;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.util.Log;

import com.android.server.connectivity.mdns.MdnsServiceInfo.TextEntry;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsServiceInfoTest {
    @Test
    public void constructor_createWithOnlyTextStrings_correctAttributes() {
        MdnsServiceInfo info =
                new MdnsServiceInfo(
                        "my-mdns-service",
                        new String[] {"_googlecast", "_tcp"},
                        List.of(),
                        new String[] {"my-host", "local"},
                        12345,
                        "192.168.1.1",
                        "2001::1",
                        List.of("vn=Google Inc.", "mn=Google Nest Hub Max"),
                        /* textEntries= */ null);

        assertTrue(info.getAttributeByKey("vn").equals("Google Inc."));
        assertTrue(info.getAttributeByKey("mn").equals("Google Nest Hub Max"));
    }

    @Test
    public void constructor_createWithOnlyTextEntries_correctAttributes() {
        MdnsServiceInfo info =
                new MdnsServiceInfo(
                        "my-mdns-service",
                        new String[] {"_googlecast", "_tcp"},
                        List.of(),
                        new String[] {"my-host", "local"},
                        12345,
                        "192.168.1.1",
                        "2001::1",
                        /* textStrings= */ null,
                        List.of(MdnsServiceInfo.TextEntry.fromString("vn=Google Inc."),
                                MdnsServiceInfo.TextEntry.fromString("mn=Google Nest Hub Max")));

        assertTrue(info.getAttributeByKey("vn").equals("Google Inc."));
        assertTrue(info.getAttributeByKey("mn").equals("Google Nest Hub Max"));
    }

    @Test
    public void constructor_createWithBothTextStringsAndTextEntries_acceptsOnlyTextEntries() {
        MdnsServiceInfo info =
                new MdnsServiceInfo(
                        "my-mdns-service",
                        new String[] {"_googlecast", "_tcp"},
                        List.of(),
                        new String[] {"my-host", "local"},
                        12345,
                        "192.168.1.1",
                        "2001::1",
                        List.of("vn=Alphabet Inc.", "mn=Google Nest Hub Max", "id=12345"),
                        List.of(
                                MdnsServiceInfo.TextEntry.fromString("vn=Google Inc."),
                                MdnsServiceInfo.TextEntry.fromString("mn=Google Nest Hub Max")));

        assertTrue(info.getAttributes().containsKey("vn"));
        assertTrue(info.getAttributes().containsKey("mn"));
        assertTrue(info.getAttributes().containsValue("Google Inc."));
        assertTrue(info.getAttributes().containsValue("Google Nest Hub Max"));
    }

    @Test
    public void constructor_createWithDuplicateKeys_acceptsTheFirstOne() {
        MdnsServiceInfo info =
                new MdnsServiceInfo(
                        "my-mdns-service",
                        new String[] {"_googlecast", "_tcp"},
                        List.of(),
                        new String[] {"my-host", "local"},
                        12345,
                        "192.168.1.1",
                        "2001::1",
                        List.of("vn=Alphabet Inc.", "mn=Google Nest Hub Max", "id=12345"),
                        List.of(MdnsServiceInfo.TextEntry.fromString("vn=Google Inc."),
                                MdnsServiceInfo.TextEntry.fromString("mn=Google Nest Hub Max"),
                                MdnsServiceInfo.TextEntry.fromString("mn=Google WiFi Router")));

        assertTrue(info.getAttributes().containsKey("vn"));
        assertTrue(info.getAttributes().containsKey("mn"));
        assertTrue(info.getAttributes().containsValue("Google Inc."));
        assertTrue(info.getAttributes().containsValue("Google Nest Hub Max"));
    }

    @Test
    public void parcelable_canBeParceledAndUnparceled() {
        Parcel parcel = Parcel.obtain();
        MdnsServiceInfo beforeParcel =
                new MdnsServiceInfo(
                        "my-mdns-service",
                        new String[] {"_googlecast", "_tcp"},
                        List.of(),
                        new String[] {"my-host", "local"},
                        12345,
                        "192.168.1.1",
                        "2001::1",
                        List.of("vn=Alphabet Inc.", "mn=Google Nest Hub Max", "id=12345"),
                        List.of(
                                MdnsServiceInfo.TextEntry.fromString("vn=Google Inc."),
                                MdnsServiceInfo.TextEntry.fromString("mn=Google Nest Hub Max")));

        beforeParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        MdnsServiceInfo afterParcel = MdnsServiceInfo.CREATOR.createFromParcel(parcel);

        assertTrue(afterParcel.getServiceInstanceName()
                .equals(beforeParcel.getServiceInstanceName()));
        assertArrayEquals(afterParcel.getServiceType(), beforeParcel.getServiceType());
        assertTrue(afterParcel.getSubtypes().equals(beforeParcel.getSubtypes()));
        assertArrayEquals(afterParcel.getHostName(), beforeParcel.getHostName());
        assertEquals(afterParcel.getPort(), beforeParcel.getPort());
        assertTrue(afterParcel.getIpv4Address().equals(beforeParcel.getIpv4Address()));
        assertTrue(afterParcel.getIpv6Address().equals(beforeParcel.getIpv6Address()));
        for (Map.Entry<String, String> kv : beforeParcel.getAttributes().entrySet()) {
            Log.d("honda", "Before key=" + kv.getKey() + ", value=" + kv.getValue());
        }
        for (Map.Entry<String, String> kv : afterParcel.getAttributes().entrySet()) {
            Log.d("honda", "After key=" + kv.getKey() + ", value=" + kv.getValue());
        }
        assertTrue(afterParcel.getAttributes().equals(beforeParcel.getAttributes()));
    }

    @Test
    public void textEntry_parcelable_canBeParceledAndUnparceled() {
        Parcel parcel = Parcel.obtain();
        TextEntry beforeParcel = new TextEntry("AA", new byte[] {(byte) 0xFF, (byte) 0xFC});

        beforeParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        TextEntry afterParcel = TextEntry.CREATOR.createFromParcel(parcel);

        assertEquals(afterParcel, beforeParcel);
    }

    @Test
    public void textEntry_fromString_keyValueAreExpected() {
        TextEntry entry = TextEntry.fromString("AA=xxyyzz");

        assertTrue(entry.getKey().equals("AA"));
        assertArrayEquals(entry.getValue(), new byte[] {'x', 'x', 'y', 'y', 'z', 'z'});
    }

    @Test
    public void textEntry_fromStringToString_textUnchanged() {
        TextEntry entry = TextEntry.fromString("AA=xxyyzz");

        assertTrue(entry.toString().equals("AA=xxyyzz"));
    }

    @Test
    public void textEntry_fromStringWithoutAssignPunc_valueisEmpty() {
        TextEntry entry = TextEntry.fromString("AA");

        assertTrue(entry.getKey().equals("AA"));
        assertArrayEquals(entry.getValue(), new byte[] {});
    }

    @Test
    public void textEntry_fromStringAssignPuncAtBeginning_returnsNull() {
        TextEntry entry = TextEntry.fromString("=AA");

        assertNull(entry);
    }

    @Test
    public void textEntry_fromBytes_keyAndValueAreExpected() {
        TextEntry entry = TextEntry.fromBytes(
                new byte[] {'A', 'A', '=', 'x', 'x', 'y', 'y', 'z', 'z'});

        assertTrue(entry.getKey().equals("AA"));
        assertArrayEquals(entry.getValue(), new byte[] {'x', 'x', 'y', 'y', 'z', 'z'});
    }

    @Test
    public void textEntry_fromBytesToBytes_textUnchanged() {
        TextEntry entry = TextEntry.fromBytes(
                new byte[] {'A', 'A', '=', 'x', 'x', 'y', 'y', 'z', 'z'});

        assertArrayEquals(entry.toBytes(),
                new byte[] {'A', 'A', '=', 'x', 'x', 'y', 'y', 'z', 'z'});
    }

    @Test
    public void textEntry_fromBytesWithoutAssignPunc_valueisEmpty() {
        TextEntry entry = TextEntry.fromBytes(new byte[] {'A', 'A'});

        assertTrue(entry.getKey().equals("AA"));
        assertArrayEquals(entry.getValue(), new byte[] {});
    }

    @Test
    public void textEntry_fromBytesAssignPuncAtBeginning_returnsNull() {
        TextEntry entry = TextEntry.fromBytes(new byte[] {'=', 'A', 'A'});

        assertNull(entry);
    }

    @Test
    public void textEntry_fromNonUtf8Bytes_keyValueAreExpected() {
        TextEntry entry = TextEntry.fromBytes(
                new byte[] {'A', 'A', '=', (byte) 0xFF, (byte) 0xFE, (byte) 0xFD});

        assertTrue(entry.getKey().equals("AA"));
        assertArrayEquals(entry.getValue(), new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD});
    }

    @Test
    public void textEntry_equals() {
        assertEquals(new TextEntry("AA", "xxyyzz"), new TextEntry("AA", "xxyyzz"));
        assertEquals(new TextEntry("BB", "xxyyzz"), new TextEntry("BB", "xxyyzz"));
        assertEquals(new TextEntry("AA", "XXYYZZ"), new TextEntry("AA", "XXYYZZ"));
    }
}
