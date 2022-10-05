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

import android.net.DnsResolver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.List;

class ProbingInfo {

    private final int mServiceId;
    private final List<MdnsRecord> mProbeRecords;
    private final DatagramPacket[] mPackets;

    /**
     * Create a new ProbingInfo
     * @param tmpBuffer Temporary buffer to use to create packets. Contents will be modified but the
     *                  buffer is unused after the constructor returns.
     * @throws IOException
     */
    ProbingInfo(int serviceId, List<MdnsRecord> probeRecords, byte[] tmpBuffer) throws IOException {
        mServiceId = serviceId;
        mProbeRecords = probeRecords;
        mPackets = makePackets(probeRecords, tmpBuffer);
    }

    public int getServiceId() {
        return mServiceId;
    }

    public List<MdnsRecord> getProbeRecords() {
        return mProbeRecords;
    }

    public DatagramPacket[] getPackets() {
        return mPackets;
    }

    private static DatagramPacket[] makePackets(List<MdnsRecord> records,
            byte[] tmpBuffer) throws IOException {
        // TODO: The max size should be the interface MTU minus IP / UDP headers (generally 28 bytes
        // for IPv4, 48 for v6). Consider what to do if the probe is bigger than that (send several
        // queries ?); probing would just fail here, which is rather OK behavior if unreasonably
        // large records have been requested.
        final MdnsPacketWriter writer = new MdnsPacketWriter(tmpBuffer);

        // Probe for query type ANY (https://datatracker.ietf.org/doc/html/rfc6762#section-8.1), but
        // put the records in the authority section
        // (https://datatracker.ietf.org/doc/html/rfc6762#section-8.2)
        writer.writeUInt16(0); // Transaction ID (0)
        writer.writeUInt16(MdnsConstants.FLAGS_QUERY);
        final int questionsCountPos = writer.getWritePosition();
        writer.writeUInt16(0); // questions count (temporary)
        writer.writeUInt16(0); // answers count
        writer.writeUInt16(records.size()); // authority entries count
        writer.writeUInt16(0); // additional records count

        // Write questions
        int numQuestions = 0;
        for (int i = 0; i < records.size(); i++) {
            final MdnsRecord record = records.get(i);
            if (containsName(records, record.getName(), i)) {
                // Already added this name
                continue;
            }

            writeQuestion(record.getName(), writer);
            numQuestions++;
        }

        writer.rewind(questionsCountPos);
        writer.writeUInt16(numQuestions);
        writer.unrewind();

        // Write authority entries
        for (MdnsRecord record : records) {
            record.write(writer, 0L /* now */);
        }

        final int len = writer.getWritePosition();
        final byte[] outBuffer = new byte[len];
        System.arraycopy(tmpBuffer, 0, outBuffer, 0, len);

        return new DatagramPacket[] {
            new DatagramPacket(outBuffer, len, MdnsAdvertiser.IPV6_ADDR),
            new DatagramPacket(outBuffer, len, MdnsAdvertiser.IPV4_ADDR),
        };
    }

    /**
     * Return whether the specified name is present among the first records of the list of records.
     *
     * This allows scanning through a small list of records, and extracting a unique list of names
     * without doing allocations.
     * @param indexLimit The maximum index (exclusive) of records to scan.
     */
    private static boolean containsName(List<MdnsRecord> records, String[] name, int indexLimit) {
        for (int i = 0; i < indexLimit; i++) {
            if (Arrays.equals(records.get(i).getName(), name)) {
                return true;
            }
        }
        return false;
    }

    private static void writeQuestion(String[] name, MdnsPacketWriter writer) throws IOException {
        writer.writeLabels(name);
        writer.writeUInt16(255); // Type: ANY
        // TODO: legacy Android mDNS used to only send the first probe as unicast, even though
        // https://datatracker.ietf.org/doc/html/rfc6762#section-8.1 says they SHOULD all be.
        // Consider using legacy behavior if this causes problems.
        // TODO: look at mdnsresponder code to see if there's some rationale
        // TODO: this won't work well if the port is shared with legacy mdnsresponder; consider
        // forcing multicast (rfc6762 15.1)
        writer.writeUInt16(
                MdnsConstants.QCLASS_UNICAST /* QU (question) flag */ | DnsResolver.CLASS_IN);
    }
}
