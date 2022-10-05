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

import android.annotation.NonNull;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;

class MdnsAnnouncer extends MdnsPacketRepeater<MdnsAnnouncer.AnnouncementInfo> {
    // https://datatracker.ietf.org/doc/html/rfc6762#section-8.3
    private static final long ANNOUNCEMENT_INITIAL_DELAY_MS = 1000L;
    private static final int ANNOUNCEMENT_COUNT = 8;

    private static final int CMD_SEND_ADVERTISEMENT = 1;

    @NonNull
    private final String mLogTag;

    static class AnnouncementInfo {
        private final NetworkInterface mIface;
        private final int mServiceId;
        // Records to announce (as answers)
        private final List<MdnsRecord> mAnnouncedRecords;
        // Records to place in the "Additional records", with NSEC negative responses
        // to mark records that have been verified unique
        private final List<MdnsRecord> mAdditionalRecords;
        private final DatagramPacket[] mPackets;

        AnnouncementInfo(NetworkInterface iface, int serviceId,
                List<MdnsRecord> announcedRecords,
                List<MdnsRecord> additionalRecords,
                byte[] tmpBuffer) throws IOException {
            mIface = iface;
            mServiceId = serviceId;
            mAnnouncedRecords = announcedRecords;
            mAdditionalRecords = additionalRecords;
            mPackets = makePackets(announcedRecords, additionalRecords, tmpBuffer);
        }

        private static DatagramPacket[] makePackets(
                List<MdnsRecord> announcedRecords, List<MdnsRecord> additionalRecords,
                byte[] tmpBuffer) throws IOException {
            // TODO: support packets over size
            final MdnsPacketWriter writer = new MdnsPacketWriter(tmpBuffer);

            writer.writeUInt16(0); // Transaction ID (advertisement: 0)
            writer.writeUInt16(0x8400); // Response, authoritative (rfc6762 18.4)
            writer.writeUInt16(0); // questions count
            writer.writeUInt16(announcedRecords.size()); // answers count
            writer.writeUInt16(0); // authority entries count
            writer.writeUInt16(additionalRecords.size()); // additional records count

            for (MdnsRecord record : announcedRecords) {
                record.write(writer, 0L);
            }

            for (MdnsRecord record : additionalRecords) {
                record.write(writer, 0L);
            }

            final int len = writer.getWritePosition();
            final byte[] outBuffer = new byte[len];
            System.arraycopy(tmpBuffer, 0, outBuffer, 0, len);

            return new DatagramPacket[] {
                new DatagramPacket(outBuffer, len, MdnsAdvertiser.IPV6_ADDR),
                new DatagramPacket(outBuffer, len, MdnsAdvertiser.IPV4_ADDR),
            };
        }
    }

    public MdnsAnnouncer(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MulticastSocket socket) {
        super(looper, socket, null /* cb */, ANNOUNCEMENT_COUNT);
        mLogTag = MdnsProber.class.getSimpleName() + "/" + interfaceTag;
    }

    @Override
    protected long getDelayMs(int nextIndex) {
        // Delay is doubled for each announcement
        return ANNOUNCEMENT_INITIAL_DELAY_MS << (nextIndex - 1);
    }

    @Override
    protected DatagramPacket[] getPackets(int index, AnnouncementInfo request) {
        return request.mPackets;
    }

    @Override
    protected String getTag() {
        return mLogTag;
    }

    // TODO: Notify MdnsRecordRepository that the records were announced for that service ID,
    // so it can update the last advertised timestamp of the associated records.
}
