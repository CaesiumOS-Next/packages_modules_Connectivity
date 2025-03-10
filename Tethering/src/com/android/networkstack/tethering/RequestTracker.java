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

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import android.net.IIntResultListener;
import android.net.TetheringManager.TetheringRequest;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper class to keep track of tethering requests.
 * The intended usage of this class is
 * 1) Add a pending request with {@link #addPendingRequest(TetheringRequest)} before asking the link
 *    layer to start.
 * 2) When the link layer is up, use {@link #getOrCreatePendingRequest(int)} to get a request to
 *    start IP serving with.
 * 3) Remove all pending requests with {@link #removeAllPendingRequests(int)}.
 * Note: This class is not thread-safe.
 */
public class RequestTracker {
    private static final String TAG = RequestTracker.class.getSimpleName();

    @NonNull
    private final boolean mUseFuzzyMatching;

    public RequestTracker(boolean useFuzzyMatching) {
        mUseFuzzyMatching = useFuzzyMatching;
    }

    private class PendingRequest {
        @NonNull
        private final TetheringRequest mTetheringRequest;

        private PendingRequest(@NonNull TetheringRequest tetheringRequest) {
            mTetheringRequest = tetheringRequest;
        }

        @NonNull
        TetheringRequest getTetheringRequest() {
            return mTetheringRequest;
        }
    }

    public enum AddResult {
        /**
         * Request was successfully added
         */
        SUCCESS,
        /**
         * Failure indicating that the request could not be added due to a request of the same type
         * with conflicting parameters already pending. If so, we must stop tethering for the
         * pending request before trying to add the result again.
         */
        FAILURE_CONFLICTING_PENDING_REQUEST,
        /**
         * Failure indicating that the request could not be added due to a fuzzy-matched request
         * already pending or serving.
         */
        FAILURE_EXISTING_FUZZY_MATCHED_REQUEST,
        /**
         * Failure indicating that the request could not be added due to the service not being
         * available.
         */
        FAILURE_SERVICE_UNAVAILABLE
    }

    /**
     * List of pending requests added by {@link #addPendingRequest(TetheringRequest,
     * IIntResultListener)}. There can be only one per type, since we remove every request of the
     * same type when we add a request.
     */
    private final List<PendingRequest> mPendingRequests = new ArrayList<>();
    // Serving IpServer interfaces mapped to their TetheringRequest.
    private final Map<String, TetheringRequest> mServingRequests = new ArrayMap<>();

    @VisibleForTesting
    List<TetheringRequest> getPendingTetheringRequests() {
        List<TetheringRequest> requests = new ArrayList<>();
        for (PendingRequest pendingRequest : mPendingRequests) {
            requests.add(pendingRequest.getTetheringRequest());
        }
        return requests;
    }

    /**
     * Adds a pending request as long as there's no fuzzy-matched request already pending or
     * serving, or returns AddResult.FAILURE_CONFLICTING_PENDING_REQUEST if there is one.
     */
    public AddResult addPendingRequestFuzzyMatched(@NonNull final TetheringRequest newRequest) {
        // Can't add request if there's an existing request (pending or serving) that fuzzy
        // matches the new request.
        for (TetheringRequest servingRequest : mServingRequests.values()) {
            if (servingRequest.fuzzyMatches(newRequest)) {
                Log.i(TAG, "Cannot add pending request due to serving fuzzy-matched "
                        + "request: " + servingRequest);
                return AddResult.FAILURE_CONFLICTING_PENDING_REQUEST;
            }
        }
        for (PendingRequest request : mPendingRequests) {
            TetheringRequest pendingRequest = request.getTetheringRequest();
            if (pendingRequest.fuzzyMatches(newRequest)) {
                Log.i(TAG, "Cannot add pending request due to pending fuzzy-matched "
                        + "request: " + pendingRequest);
                return AddResult.FAILURE_CONFLICTING_PENDING_REQUEST;
            }
        }
        mPendingRequests.add(new PendingRequest(newRequest));
        return AddResult.SUCCESS;
    }

    /**
     * Add a pending request and listener. The request should be added before asking the link layer
     * to start, and should be retrieved with {@link #getNextPendingRequest(int)} once the link
     * layer comes up. The result of the add operation will be returned as an AddResult code.
     */
    public AddResult addPendingRequest(@NonNull final TetheringRequest newRequest) {
        if (mUseFuzzyMatching) {
            return addPendingRequestFuzzyMatched(newRequest);
        }

        // Check the existing requests to see if it is OK to add the new request.
        for (PendingRequest request : mPendingRequests) {
            TetheringRequest existingRequest = request.getTetheringRequest();
            if (existingRequest.getTetheringType() != newRequest.getTetheringType()) {
                continue;
            }

            // Can't add request if there's a request of the same type with different
            // parameters.
            if (!existingRequest.equalsIgnoreUidPackage(newRequest)) {
                return AddResult.FAILURE_CONFLICTING_PENDING_REQUEST;
            }
        }

        // Remove the existing pending request of the same type. We already filter out for
        // conflicting parameters above, so these would have been equivalent anyway (except for
        // UID).
        removeAllPendingRequests(newRequest.getTetheringType());
        mPendingRequests.add(new PendingRequest(newRequest));
        return AddResult.SUCCESS;
    }

    /**
     * Gets the next pending TetheringRequest of a given type, or creates a placeholder request if
     * there are none.
     * Note: There are edge cases where the pending request is absent and we must temporarily
     * synthesize a placeholder request, such as if stopTethering was called before link
     * layer went up, or if the link layer goes up without us poking it (e.g. adb shell
     * cmd wifi start-softap). These placeholder requests only specify the tethering type
     * and the default connectivity scope.
     */
    @NonNull
    public TetheringRequest getOrCreatePendingRequest(int type) {
        TetheringRequest pending = getNextPendingRequest(type);
        if (pending != null) return pending;

        Log.w(TAG, "No pending TetheringRequest for type " + type + " found, creating a"
                + " placeholder request");
        return createPlaceholderRequest(type);
    }

    /**
     * Same as {@link #getOrCreatePendingRequest(int)} but returns {@code null} if there's no
     * pending request found.
     *
     * @param type Tethering type of the pending request
     * @return pending request or {@code null} if there are none.
     */
    @Nullable
    public TetheringRequest getNextPendingRequest(int type) {
        for (PendingRequest pendingRequest : mPendingRequests) {
            TetheringRequest tetheringRequest =
                    pendingRequest.getTetheringRequest();
            if (tetheringRequest.getTetheringType() == type) return tetheringRequest;
        }
        return null;
    }

    /**
     * Removes all pending requests of the given tethering type.
     *
     * @param type Tethering type
     */
    public void removeAllPendingRequests(int type) {
        mPendingRequests.removeIf(r -> r.getTetheringRequest().getTetheringType() == type);
    }

    /**
     * Adds a serving request for the specified iface.
     */
    public void setServingRequest(@NonNull String iface,
            @NonNull final TetheringRequest tetheringRequest) {
        mServingRequests.put(iface, tetheringRequest);
    }

    /**
     * Gets the serving request for the specified iface if there is one, or {@code null} if there is
     * none.
     */
    @Nullable
    public TetheringRequest getServingRequest(@NonNull final String iface) {
        return mServingRequests.get(iface);
    }

    /**
     * Removes the serving request for the specified iface.
     */
    public void removeServingRequest(@NonNull String iface) {
        mServingRequests.remove(iface);
    }

    /**
     * Returns an existing (pending or serving) request that fuzzy matches the given request.
     * Optionally specify matchUid to only return requests with the same uid.
     */
    public TetheringRequest findFuzzyMatchedRequest(
            @NonNull final TetheringRequest tetheringRequest, boolean matchUid) {
        List<TetheringRequest> allRequests = new ArrayList<>();
        allRequests.addAll(getPendingTetheringRequests());
        allRequests.addAll(mServingRequests.values());
        for (TetheringRequest request : allRequests) {
            if (!request.fuzzyMatches(tetheringRequest)) continue;
            if (matchUid && tetheringRequest.getUid() != request.getUid()) continue;
            return request;
        }
        return null;
    }
}
