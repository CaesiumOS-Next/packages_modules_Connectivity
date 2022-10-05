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

import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.util.List;

public class MdnsProber extends MdnsPacketRepeater<ProbingInfo> {
    private static final String TAG = MdnsProber.class.getSimpleName();
    private static final long CONFLICT_RETRY_DELAY_MS = 5_000L;
    private final MdnsRecordRepository mRepository;

    public MdnsProber(Looper looper, MulticastSocket socket, MdnsRecordRepository repository,
            PacketRepeaterCallback<ProbingInfo> cb) {
        super(looper, socket, cb, 3 /* numPackets */);
        mRepository = repository;
    }

    @Override
    protected long getDelayMs(int nextIndex) {
        // As per https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
        return 250L;
    }

    @Override
    protected DatagramPacket[] getPackets(int index, ProbingInfo request) {
        return request.getPackets();
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @VisibleForTesting
    protected long getInitialDelay() {
        // First wait for a random time in 0-250ms
        // as per https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
        return (long) (Math.random() * 250);
    }

    public void startProbing(ProbingInfo info) {
        startProbing(info, getInitialDelay());
    }

    private void startProbing(ProbingInfo info, long delay) {
        startSending(info.getServiceId(), info, delay);
    }

    /**
     * If the incoming packet conflicts with any records, update records and restart probing on
     * all conflicting services.
     *
     * As per https://datatracker.ietf.org/doc/html/rfc6762#section-9, this is done regardless of
     * whether probing is in progress.
     * @param packet Incoming packet
     * @return The new probing info of services that were reset to probing
     */
    public List<ProbingInfo> maybeHandleConflict(@NonNull MdnsPacket packet) {
        final List<ProbingInfo> newInfo = mRepository.maybeUpdateForConflicts(packet);
        for (ProbingInfo info : newInfo) {
            stop(info.getServiceId());

            /* RFC 6762 8.1: "If fifteen conflicts occur within any ten-second period, then the host
            MUST wait at least five seconds before each successive additional probe attempt. [...]
            For very simple devices, a valid way to comply with this requirement is to always wait
            five seconds after any failed probe attempt before trying again. */
            // TODO: count 15 conflicts in 10s instead of waiting for 5s every time
            startProbing(info, CONFLICT_RETRY_DELAY_MS);
        }
        return newInfo;
    }
}
