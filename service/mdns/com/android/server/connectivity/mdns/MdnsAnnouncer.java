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

import com.android.internal.annotations.VisibleForTesting;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

class MdnsAnnouncer extends MdnsPacketRepeater<MdnsAnnouncer.AnnouncementInfo> {
    // https://datatracker.ietf.org/doc/html/rfc6762#section-8.3
    private static final long ANNOUNCEMENT_INITIAL_DELAY_MS = 1000L;
    @VisibleForTesting
    static final int ANNOUNCEMENT_COUNT = 8;

    @NonNull
    private final String mLogTag;

    static class AnnouncementInfo implements MdnsPacketRepeater.Request {
        @NonNull
        private final MdnsPacket mPacket;
        @NonNull
        private final Supplier<Iterable<SocketAddress>> mDestinationsSupplier;

        AnnouncementInfo(List<MdnsRecord> announcedRecords, List<MdnsRecord> additionalRecords,
                Supplier<Iterable<SocketAddress>> destinationsSupplier) {
            // Records to announce (as answers)
            // Records to place in the "Additional records", with NSEC negative responses
            // to mark records that have been verified unique
            final int flags = 0x8400; // Response, authoritative (rfc6762 18.4)
            mPacket = new MdnsPacket(flags,
                    Collections.emptyList() /* questions */,
                    announcedRecords,
                    Collections.emptyList() /* authorityRecords */,
                    additionalRecords);
            mDestinationsSupplier = destinationsSupplier;
        }

        @Override
        public MdnsPacket getPacket(int index) {
            return mPacket;
        }

        @Override
        public Iterable<SocketAddress> getDestinations(int index) {
            return mDestinationsSupplier.get();
        }
    }

    MdnsAnnouncer(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MdnsReplySender replySender) {
        super(looper, replySender, null /* cb */, ANNOUNCEMENT_COUNT);
        mLogTag = MdnsProber.class.getSimpleName() + "/" + interfaceTag;
    }

    @Override
    protected long getDelayMs(int nextIndex) {
        // Delay is doubled for each announcement
        return ANNOUNCEMENT_INITIAL_DELAY_MS << (nextIndex - 1);
    }

    @Override
    protected String getTag() {
        return mLogTag;
    }

    // TODO: Notify MdnsRecordRepository that the records were announced for that service ID,
    // so it can update the last advertised timestamp of the associated records.
}
