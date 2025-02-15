/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_BLUETOOTH_SERVICE_PENDING;
import static android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import static com.google.common.truth.Truth.assertThat;

import android.net.TetheringManager.TetheringRequest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.networkstack.tethering.RequestTracker.AddResult;
import com.android.networkstack.tethering.TetheringTest.ResultListener;
import com.android.networkstack.tethering.metrics.TetheringMetrics;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RequestTrackerTest {
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock TetheringMetrics mTetheringMetrics;

    private RequestTracker mRequestTracker;

    @Before
    public void setUp() {
        mRequestTracker = new RequestTracker(mTetheringMetrics);
    }

    @Test
    public void testNoRequestsAdded_noPendingRequests() {
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testAddRequest_successAndBecomesNextPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();

        final AddResult result = mRequestTracker.addPendingRequest(request, null);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_equalRequestExists_successButDoesNotBecomeNextPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest equalRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final AddResult result = mRequestTracker.addPendingRequest(equalRequest, null);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_equalButDifferentUidRequest_successButDoesNotBecomeNextPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        request.setUid(1000);
        request.setPackageName("package");
        final TetheringRequest differentUid = new TetheringRequest.Builder(TETHERING_WIFI).build();
        differentUid.setUid(2000);
        differentUid.setPackageName("package2");
        mRequestTracker.addPendingRequest(request, null);

        final int result = mRequestTracker.addPendingRequest(differentUid, null);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(request);
    }

    @Test
    public void testAddConflictingRequest_returnsFailureConflictingPendingRequest() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest conflictingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                .setExemptFromEntitlementCheck(true).build();
        mRequestTracker.addPendingRequest(request, null);

        final AddResult result = mRequestTracker.addPendingRequest(conflictingRequest, null);

        assertThat(result).isEqualTo(AddResult.FAILURE_CONFLICTING_PENDING_REQUEST);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testRemoveAllPendingRequests_noPendingRequestsLeft() {
        final TetheringRequest firstRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        firstRequest.setUid(1000);
        firstRequest.setPackageName("package");
        mRequestTracker.addPendingRequest(firstRequest, null);
        final TetheringRequest secondRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        secondRequest.setUid(2000);
        secondRequest.setPackageName("package2");
        mRequestTracker.addPendingRequest(secondRequest, null);

        mRequestTracker.removeAllPendingRequests(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testRemoveAllPendingRequests_differentTypeExists_doesNotRemoveDifferentType() {
        final TetheringRequest differentType = new TetheringRequest.Builder(TETHERING_USB).build();
        mRequestTracker.addPendingRequest(differentType, null);

        mRequestTracker.removeAllPendingRequests(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_USB)).isEqualTo(differentType);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_USB))
                .isEqualTo(differentType);
    }

    @Test
    public void testSendLinkLayerResult_resultSentToCorrectRequest() {
        final TetheringRequest wifiRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        ResultListener wifiListener = new ResultListener(TETHER_ERROR_NO_ERROR);
        mRequestTracker.addPendingRequest(wifiRequest, wifiListener);
        final TetheringRequest usbRequest = new TetheringRequest.Builder(TETHERING_USB).build();
        ResultListener usbListener = new ResultListener(TETHER_ERROR_SERVICE_UNAVAIL);
        mRequestTracker.addPendingRequest(usbRequest, usbListener);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(wifiRequest);

        mRequestTracker.sendLinkLayerResult(wifiRequest, TETHER_ERROR_NO_ERROR);
        mRequestTracker.sendLinkLayerResult(usbRequest, TETHER_ERROR_SERVICE_UNAVAIL);

        wifiListener.assertHasResult();
        usbListener.assertHasResult();
    }

    @Test
    public void testSendLinkLayerResult_successResult_requestIsStillPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        ResultListener listener = new ResultListener(TETHER_ERROR_NO_ERROR);
        mRequestTracker.addPendingRequest(request, listener);

        mRequestTracker.sendLinkLayerResult(request, TETHER_ERROR_NO_ERROR);

        listener.assertHasResult();
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testSendLinkLayerResult_failureResult_requestIsRemoved() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        ResultListener listener = new ResultListener(TETHER_ERROR_INTERNAL_ERROR);
        mRequestTracker.addPendingRequest(request, listener);

        mRequestTracker.sendLinkLayerResult(request, TETHER_ERROR_INTERNAL_ERROR);

        listener.assertHasResult();
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testSendLinkLayerResult_pendingBluetoothResult_listenerNotNotified() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener = new ResultListener(TETHER_ERROR_BLUETOOTH_SERVICE_PENDING);
        mRequestTracker.addPendingRequest(request, listener);

        mRequestTracker.sendLinkLayerResult(request, TETHER_ERROR_BLUETOOTH_SERVICE_PENDING);

        listener.assertDoesNotHaveResult();
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_BLUETOOTH)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_BLUETOOTH))
                .isEqualTo(request);
    }

    @Test
    public void testSendLinkLayerResult_multipleRequestsSameTypeSuccess_allListenersNotified() {
        final TetheringRequest request1 = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener1 = new ResultListener(TETHER_ERROR_NO_ERROR);
        mRequestTracker.addPendingRequest(request1, listener1);
        final TetheringRequest request2 = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener2 = new ResultListener(TETHER_ERROR_NO_ERROR);
        mRequestTracker.addPendingRequest(request2, listener2);
        final TetheringRequest request3 = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener3 = new ResultListener(TETHER_ERROR_NO_ERROR);
        mRequestTracker.addPendingRequest(request3, listener3);

        mRequestTracker.sendLinkLayerResult(request1, TETHER_ERROR_NO_ERROR);

        listener1.assertHasResult();
        listener2.assertHasResult();
        listener3.assertHasResult();
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_BLUETOOTH)).isEqualTo(request1);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_BLUETOOTH))
                .isEqualTo(request1);
    }

    @Test
    public void testSendLinkLayerResult_multipleRequestsSameTypeFailure_allRequestsRemoved() {
        final TetheringRequest request1 = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener1 = new ResultListener(TETHER_ERROR_INTERNAL_ERROR);
        mRequestTracker.addPendingRequest(request1, listener1);
        final TetheringRequest request2 = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener2 = new ResultListener(TETHER_ERROR_INTERNAL_ERROR);
        mRequestTracker.addPendingRequest(request2, listener2);
        final TetheringRequest request3 = new TetheringRequest.Builder(TETHERING_BLUETOOTH).build();
        ResultListener listener3 = new ResultListener(TETHER_ERROR_INTERNAL_ERROR);
        mRequestTracker.addPendingRequest(request3, listener3);

        mRequestTracker.sendLinkLayerResult(request1, TETHER_ERROR_INTERNAL_ERROR);

        listener1.assertHasResult();
        listener2.assertHasResult();
        listener3.assertHasResult();
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_BLUETOOTH)).isEqualTo(null);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_BLUETOOTH))
                .isEqualTo(createPlaceholderRequest(TETHERING_BLUETOOTH));
    }
}
