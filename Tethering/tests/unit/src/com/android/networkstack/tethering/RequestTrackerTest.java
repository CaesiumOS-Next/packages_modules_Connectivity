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

import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import static com.google.common.truth.Truth.assertThat;

import android.net.TetheringManager.TetheringRequest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RequestTrackerTest {
    @Test
    public void testNoRequestsAdded_noPendingRequests() {
        final RequestTracker requestTracker = new RequestTracker();

        assertThat(requestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testAddRequest_requestBecomesNextPending() {
        final RequestTracker requestTracker = new RequestTracker();
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();

        final int result = requestTracker.addPendingRequest(request);

        assertThat(result).isEqualTo(RequestTracker.ADD_RESULT_SUCCESS);
        assertThat(requestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_equalRequestExists_succeedsButDoesntBecomeNext() {
        final RequestTracker requestTracker = new RequestTracker();
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        requestTracker.addPendingRequest(request);

        final TetheringRequest equalRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final int result = requestTracker.addPendingRequest(equalRequest);

        assertThat(result).isEqualTo(RequestTracker.ADD_RESULT_SUCCESS);
        assertThat(requestTracker.getNextPendingRequest(TETHERING_WIFI))
                .isSameInstanceAs(request);
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isSameInstanceAs(request);
    }

    @Test
    public void testAddRequest_equalButDifferentUidRequestExists_succeedsButDoesntBecomeNext() {
        final RequestTracker requestTracker = new RequestTracker();
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        request.setUid(1000);
        request.setPackageName("package");
        final TetheringRequest differentUid = new TetheringRequest.Builder(TETHERING_WIFI).build();
        differentUid.setUid(2000);
        differentUid.setPackageName("package2");
        requestTracker.addPendingRequest(request);

        final int result = requestTracker.addPendingRequest(differentUid);

        assertThat(result).isEqualTo(RequestTracker.ADD_RESULT_SUCCESS);
        assertThat(requestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddConflictingRequest_requestIsRejected() {
        final RequestTracker requestTracker = new RequestTracker();
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest conflictingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                .setExemptFromEntitlementCheck(true).build();
        requestTracker.addPendingRequest(request);

        final int result = requestTracker.addPendingRequest(conflictingRequest);

        assertThat(result).isEqualTo(RequestTracker.ADD_RESULT_FAILURE_CONFLICTING_PENDING_REQUEST);
        assertThat(requestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testRemoveAllPendingRequests_noPendingRequestsLeft() {
        final RequestTracker requestTracker = new RequestTracker();
        final TetheringRequest firstRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        firstRequest.setUid(1000);
        firstRequest.setPackageName("package");
        requestTracker.addPendingRequest(firstRequest);
        final TetheringRequest secondRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        secondRequest.setUid(2000);
        secondRequest.setPackageName("package2");
        requestTracker.addPendingRequest(secondRequest);

        requestTracker.removeAllPendingRequests(TETHERING_WIFI);

        assertThat(requestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testRemoveAllPendingRequests_differentTypeExists_doesNotRemoveDifferentType() {
        final RequestTracker requestTracker = new RequestTracker();
        final TetheringRequest differentType = new TetheringRequest.Builder(TETHERING_USB).build();
        requestTracker.addPendingRequest(differentType);

        requestTracker.removeAllPendingRequests(TETHERING_WIFI);

        assertThat(requestTracker.getNextPendingRequest(TETHERING_USB)).isEqualTo(differentType);
        assertThat(requestTracker.getOrCreatePendingRequest(TETHERING_USB))
                .isEqualTo(differentType);
    }
}
