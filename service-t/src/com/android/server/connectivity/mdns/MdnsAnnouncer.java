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
import android.annotation.Nullable;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sends mDns announcements when a service registration changes and at regular intervals.
 *
 * This allows maintaining other hosts' caches up-to-date. See RFC6762 8.3.
 */
public class MdnsAnnouncer extends MdnsPacketRepeater<MdnsAnnouncer.BaseAnnouncementInfo> {
    private static final long ANNOUNCEMENT_INITIAL_DELAY_MS = 1000L;
    @VisibleForTesting
    static final int ANNOUNCEMENT_COUNT = 8;

    // Matches delay and GoodbyeCount used by the legacy implementation
    private static final long EXIT_DELAY_MS = 2000L;
    private static final int EXIT_COUNT = 3;

    @NonNull
    private final String mLogTag;

    abstract static class BaseAnnouncementInfo implements MdnsPacketRepeater.Request {
        private final int mServiceId;
        @NonNull
        private final MdnsPacket mPacket;
        @NonNull
        private final Supplier<Iterable<SocketAddress>> mDestinationsSupplier;

        protected BaseAnnouncementInfo(int serviceId, @NonNull List<MdnsRecord> announcedRecords,
                @NonNull List<MdnsRecord> additionalRecords,
                @NonNull Supplier<Iterable<SocketAddress>> destinationsSupplier) {
            mServiceId = serviceId;
            final int flags = 0x8400; // Response, authoritative (rfc6762 18.4)
            mPacket = new MdnsPacket(flags,
                    Collections.emptyList() /* questions */,
                    announcedRecords,
                    Collections.emptyList() /* authorityRecords */,
                    additionalRecords);
            mDestinationsSupplier = destinationsSupplier;
        }

        public int getServiceId() {
            return mServiceId;
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

    static class AnnouncementInfo extends BaseAnnouncementInfo {
        AnnouncementInfo(int serviceId, List<MdnsRecord> announcedRecords,
                List<MdnsRecord> additionalRecords,
                Supplier<Iterable<SocketAddress>> destinationsSupplier) {
            super(serviceId, announcedRecords, additionalRecords, destinationsSupplier);
        }

        @Override
        public long getDelayMs(int nextIndex) {
            // Delay is doubled for each announcement
            return ANNOUNCEMENT_INITIAL_DELAY_MS << (nextIndex - 1);
        }

        @Override
        public int getNumSends() {
            return ANNOUNCEMENT_COUNT;
        }
    }

    static class ExitAnnouncementInfo extends BaseAnnouncementInfo {
        ExitAnnouncementInfo(int serviceId, List<MdnsRecord> announcedRecords,
                Supplier<Iterable<SocketAddress>> destinationsSupplier) {
            super(serviceId, announcedRecords, Collections.emptyList(), destinationsSupplier);
        }

        @Override
        public long getDelayMs(int nextIndex) {
            return EXIT_DELAY_MS;
        }

        @Override
        public int getNumSends() {
            return EXIT_COUNT;
        }
    }

    public MdnsAnnouncer(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MdnsReplySender replySender,
            @Nullable PacketRepeaterCallback<BaseAnnouncementInfo> cb) {
        super(looper, replySender, cb);
        mLogTag = MdnsAnnouncer.class.getSimpleName() + "/" + interfaceTag;
    }

    @Override
    protected String getTag() {
        return mLogTag;
    }

    // TODO: Notify MdnsRecordRepository that the records were announced for that service ID,
    // so it can update the last advertised timestamp of the associated records.
}
