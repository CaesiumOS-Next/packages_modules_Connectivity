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

package com.android.server

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P
import android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProvider
import android.net.NetworkProvider.NetworkOfferCallback
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Reserved
import com.android.testutils.RecorderCallback.CallbackEntry.Unavailable
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkOfferCallback
import com.android.testutils.TestableNetworkOfferCallback.CallbackEntry.OnNetworkNeeded
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


private val ETHERNET_SCORE = NetworkScore.Builder().build()
private val ETHERNET_CAPS = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val ETHERNET_REQUEST = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()

private const val TIMEOUT_MS = 5_000L
private const val NO_CB_TIMEOUT_MS = 200L

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSNetworkReservationTest : CSTest() {
    private lateinit var provider: NetworkProvider

    @Before
    fun subclassSetUp() {
        provider = NetworkProvider(context, csHandlerThread.looper, "Ethernet provider")
        cm.registerNetworkProvider(provider)
    }

    fun NetworkCapabilities.copyWithReservationId(resId: Int) = NetworkCapabilities(this).also {
        it.reservationId = resId
    }

    fun NetworkProvider.registerNetworkOffer(
            score: NetworkScore,
            caps: NetworkCapabilities,
            cb: NetworkOfferCallback)
    {
        registerNetworkOffer(score, caps, {r -> r.run()}, cb)
    }

    @Test
    fun testReservationRequest() {
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, blanketOfferCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up specific reservation offer
        val specificCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val specificOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, specificCaps, specificOfferCb)

        // validate onReserved was sent to the app
        val reservedCaps = cb.expect<Reserved>().caps
        assertEquals(specificCaps, reservedCaps)

        // validate the reservation matches the specific offer.
        specificOfferCb.expectOnNetworkNeeded(specificCaps)

        // Specific offer goes away
        provider.unregisterNetworkOffer(specificOfferCb)
        cb.expect<Unavailable>()
    }

    fun TestableNetworkOfferCallback.expectNoCallbackWhere(
            predicate: (TestableNetworkOfferCallback.CallbackEntry) -> Boolean
    ) {
        val event = history.poll(NO_CB_TIMEOUT_MS) { predicate(it) }
        assertNull(event)
    }

    @Test
    fun testReservationRequest_notDeliveredToRegularOffer() {
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, ETHERNET_CAPS, {r -> r.run()}, offerCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)

        // validate the offer does not receive onNetworkNeeded for reservation request
        offerCb.expectNoCallbackWhere {
            it is OnNetworkNeeded && it.request.type == NetworkRequest.Type.RESERVATION
        }
    }

    @Test
    fun testReservationOfferMigration_reservationIdCannotBeUpdated() {
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, blanketOfferCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up specific reservation offer
        val specificCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val specificOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, specificCaps, specificOfferCb)

        cb.expect<Reserved>()
        specificOfferCb.expectOnNetworkNeeded(specificCaps)

        // try to update the offer's reservationId by reusing the same callback object.
        // first file a new request to try and match the offer later.
        val cb2 = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb2, csHandler)

        val reservationReq2 = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId2 = reservationReq2.networkCapabilities.reservationId

        // try to update the offer's capabilities by reusing the same callback object.
        val updatedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId + 100)
        provider.registerNetworkOffer(ETHERNET_SCORE, updatedCaps, specificOfferCb)

        // validate the original offer disappeared
        cb.expect<Unavailable>()

        // validate the new offer was rejected by CS
        specificOfferCb.expectOnNetworkUnneeded(specificCaps)
        cb2.assertNoCallback()
    }

    @Test
    fun testReservationOfferMigration_capabilitiesCannotBeUpdated() {
        // TODO: consider moving blanket offer registration into setUp
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, blanketOfferCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)

        val reservationReq = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        val specificCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val specificOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, specificCaps, specificOfferCb)

        cb.expect<Reserved>()
        specificOfferCb.expectOnNetworkNeeded(specificCaps)

        // update specific offer capabilities
        val updatedCaps = NetworkCapabilities(specificCaps).addCapability(NET_CAPABILITY_WIFI_P2P)
        provider.registerNetworkOffer(ETHERNET_SCORE, updatedCaps, specificOfferCb)

        cb.expect<Unavailable>()
        specificOfferCb.expectOnNetworkUnneeded(specificCaps)
        specificOfferCb.assertNoCallback()
    }

    @Test
    fun testBlanketOfferMigration() {
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, blanketOfferCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)
        blanketOfferCb.expectOnNetworkNeeded(blanketCaps)

        val updatedCaps = NetworkCapabilities(blanketCaps).addCapability(NET_CAPABILITY_WIFI_P2P)
        provider.registerNetworkOffer(ETHERNET_SCORE, updatedCaps, blanketOfferCb)
        blanketOfferCb.assertNoCallback()
    }

    @Test
    fun testReservationOffer_onlyAllowSingleOffer() {
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, blanketOfferCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)

        val reservationReq = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val caps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        provider.registerNetworkOffer(ETHERNET_SCORE, caps, offerCb)
        offerCb.expectOnNetworkNeeded(caps)
        cb.expect<Reserved>()

        val newOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, caps, newOfferCb)
        newOfferCb.assertNoCallback()
        cb.assertNoCallback()
    }

    @Test
    fun testReservationOffer_updateScore() {
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, blanketOfferCb)

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, cb, csHandler)

        val reservationReq = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        val specificCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val specificOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, specificCaps, specificOfferCb)
        specificOfferCb.expectOnNetworkNeeded(specificCaps)
        specificOfferCb.assertNoCallback()
        cb.expect<Reserved>()

        // update specific offer capabilities
        val newScore = NetworkScore.Builder().setShouldYieldToBadWifi(true).build()
        provider.registerNetworkOffer(newScore, specificCaps, specificOfferCb)
        cb.assertNoCallback()

        val cb2 = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb2, csHandler)
        specificOfferCb.expectOnNetworkNeeded(specificCaps)
        specificOfferCb.assertNoCallback()
    }

    @Test
    fun testReservationOffer_regularOfferCanBeUpdated() {
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, ETHERNET_CAPS, offerCb)

        val cb = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb, csHandler)
        offerCb.expectOnNetworkNeeded(ETHERNET_CAPS)
        offerCb.assertNoCallback()

        val updatedCaps = NetworkCapabilities(ETHERNET_CAPS).addCapability(NET_CAPABILITY_WIFI_P2P)
        val newScore = NetworkScore.Builder().setShouldYieldToBadWifi(true).build()
        provider.registerNetworkOffer(newScore, updatedCaps, offerCb)
        offerCb.assertNoCallback()

        val cb2 = TestableNetworkCallback()
        cm.requestNetwork(ETHERNET_REQUEST, cb, csHandler)
        offerCb.expectOnNetworkNeeded(ETHERNET_CAPS)
        offerCb.assertNoCallback()
    }


    // Add tests for:
    // blanket offer can be updated
    // score can be updated for a reservation offer.
    // unneeded callbacks are called if migration is rejected.
    // ensure reservationId cannot be updated (this falls under a migration).
    // no two reservation offers with same id.
}
