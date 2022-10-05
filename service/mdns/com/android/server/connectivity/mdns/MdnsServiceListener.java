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

import java.util.List;

/**
 * Base listener interface for mDNS service instance events.
 *
 * @hide
 */
public interface MdnsServiceListener {
    /**
     * Called when a mDNS service query has been sent.
     *
     * @param subtypes      The list of subtypes in the query.
     * @param transactionId The transaction ID of the query.
     */
    void onQuerySent(@NonNull List<String> subtypes, int transactionId);

    /**
     * Called when an error has happened when parsing a received mDNS response packet.
     *
     * @param receivedPacketNumber The packet sequence number of the received packet.
     * @param errorCode            The error code, defined in {@link MdnsResponseErrorCode}.
     */
    void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode);
}
