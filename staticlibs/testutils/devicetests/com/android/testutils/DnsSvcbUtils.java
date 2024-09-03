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

package com.android.testutils;

import static com.android.net.module.util.DnsPacketUtils.DnsRecordParser.domainNameToLabels;
import static com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_LEN;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_LEN;

import static org.junit.Assert.fail;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.net.InetAddresses;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DnsSvcbUtils {
    private static final Pattern SVC_PARAM_PATTERN = Pattern.compile("([a-z0-9-]+)=?(.*)");

    /**
     * Returns a DNS SVCB response with given hostname `hostname` and given SVCB records
     * `records`. Each record must contain the service priority, the target name, and the service
     * parameters.
     *     E.g. "1 doh.google alpn=h2,h3 port=443 ipv4hint=192.0.2.1 dohpath=/dns-query{?dns}"
     */
    public static byte[] makeSvcbResponse(String hostname, String[] records) throws IOException {
        if (records == null) return new byte[0];
        if (!hostname.startsWith("_dns.")) return new byte[0];
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        // Write DNS header.
        os.write(new byte[] {
                0x12, 0x34, /* Transaction ID */
                (byte) 0x81, 0x00, /* Flags */
                0x00, 0x01, /* qdcount */
                0x00, (byte) records.length, /* ancount */
                0x00, 0x00, /* nscount */
                0x00, 0x00, /* arcount */
        });
        // Write Question.
        // - domainNameToLabels() doesn't support the hostname starting with "_", so divide
        //   the writing into two steps.
        os.write(new byte[] { 0x04, '_', 'd', 'n', 's' });
        os.write(domainNameToLabels(hostname.substring(5)));
        os.write(new byte[] {
                0x00, 0x40, /* Type */
                0x00, 0x01, /* Class */
        });
        // Write Answer section.
        for (String r : records) {
            os.write(makeSvcbRecord(r));
        }
        return os.toByteArray();
    }
    private static byte[] makeSvcbRecord(String representation) throws IOException {
        if (representation == null) return new byte[0];
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(new byte[] {
                (byte) 0xc0, 0x0c, /* Name */
                0x00, 0x40, /* Type */
                0x00, 0x01, /* Class = IN */
                0x00, 0x00, 0x00, 0x10, /* TTL = 16 */
                0x00, 0x00, /* Data Length = 0*/
        });
        String[] strings = representation.split(" +");
        // SvcPriority and TargetName are mandatory in the representation.
        if (strings.length < 3) {
            fail("Invalid SVCB representation: " + representation);
        }
        // Write SvcPriority, TargetName, and SvcParams.
        os.write(shortToByteArray(Short.parseShort(strings[0])));
        os.write(domainNameToLabels(strings[1]));
        for (int i = 2; i < strings.length; i++) {
            try {
                os.write(svcParamToByteArray(strings[i]));
            } catch (UnsupportedEncodingException e) {
                throw new IOException(e);
            }
        }
        // Update rdata length.
        byte[] out = os.toByteArray();
        ByteBuffer.wrap(out).putShort(10, (short) (out.length - 12));
        return out;
    }
    private static byte[] svcParamToByteArray(String svcParam) throws IOException,
            UnsupportedEncodingException {
        final Matcher matcher = SVC_PARAM_PATTERN.matcher(svcParam);
        if (!matcher.matches() || matcher.groupCount() != 2) {
            fail("Invalid SvcParam: " + svcParam);
        }
        final String svcParamkey = matcher.group(1);
        final String svcParamValue = matcher.group(2);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(svcParamKeyToBytes(svcParamkey));
        switch (svcParamkey) {
            case "mandatory":
                String[] keys = svcParamValue.split(",");
                os.write(shortToByteArray((short) keys.length));
                for (String v : keys) {
                    os.write(svcParamKeyToBytes(v));
                }
                break;
            case "alpn":
                os.write(shortToByteArray((short) (svcParamValue.length() + 1)));
                for (String v : svcParamValue.split(",")) {
                    os.write(v.length());
                    os.write(v.getBytes(US_ASCII));
                }
                break;
            case "no-default-alpn":
                os.write(shortToByteArray((short) 0));
                break;
            case "port":
                os.write(shortToByteArray((short) 2));
                os.write(shortToByteArray(Short.parseShort(svcParamValue)));
                break;
            case "ipv4hint":
                String[] v4Addrs = svcParamValue.split(",");
                os.write(shortToByteArray((short) (v4Addrs.length * IPV4_ADDR_LEN)));
                for (String v : v4Addrs) {
                    os.write(InetAddresses.parseNumericAddress(v).getAddress());
                }
                break;
            case "ech":
                os.write(shortToByteArray((short) svcParamValue.length()));
                os.write(svcParamValue.getBytes(US_ASCII));
                break;
            case "ipv6hint":
                String[] v6Addrs = svcParamValue.split(",");
                os.write(shortToByteArray((short) (v6Addrs.length * IPV6_ADDR_LEN)));
                for (String v : v6Addrs) {
                    os.write(InetAddresses.parseNumericAddress(v).getAddress());
                }
                break;
            case "dohpath":
                os.write(shortToByteArray((short) svcParamValue.length()));
                os.write(svcParamValue.getBytes(US_ASCII));
                break;
            default:
                os.write(shortToByteArray((short) svcParamValue.length()));
                os.write(svcParamValue.getBytes(US_ASCII));
                break;
        }
        return os.toByteArray();
    }
    private static byte[] svcParamKeyToBytes(String key) {
        switch (key) {
            case "mandatory": return new byte[] { 0x00, 0x00 };
            case "alpn": return new byte[] { 0x00, 0x01 };
            case "no-default-alpn": return new byte[] { 0x00, 0x02 };
            case "port": return new byte[] { 0x00, 0x03 };
            case "ipv4hint": return new byte[] { 0x00, 0x04 };
            case "ech": return new byte[] { 0x00, 0x05 };
            case "ipv6hint": return new byte[] { 0x00, 0x06 };
            case "dohpath": return new byte[] { 0x00, 0x07 };
            default:
                if (!key.startsWith("key")) fail("Invalid SvcParamKey " + key);
                return shortToByteArray(Short.parseShort(key.substring(3)));
        }
    }
    private static byte[] shortToByteArray(short val) {
        return ByteBuffer.allocate(2).putShort(val).array();
    }
}
