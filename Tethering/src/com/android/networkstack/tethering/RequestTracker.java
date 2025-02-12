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

import static android.net.TetheringManager.TETHER_ERROR_BLUETOOTH_SERVICE_PENDING;
import static android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import android.net.IIntResultListener;
import android.net.TetheringManager.TetheringRequest;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.networkstack.tethering.metrics.TetheringMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper class to keep track of tethering requests.
 * The intended usage of this class is
 * 1) Add a pending request with {@link #addPendingRequest(TetheringRequest, IIntResultListener)}
 *    before asking the link layer to start.
 * 2) When the link layer is up, use {@link #getOrCreatePendingRequest(int)} to get a request to
 *    start IP serving with.
 * 3) Remove all pending requests with {@link #removeAllPendingRequests(int, int)}.
 * Note: This class is not thread-safe.
 */
public class RequestTracker {
    private static final String TAG = RequestTracker.class.getSimpleName();

    @NonNull
    private final boolean mUseFuzzyMatching;
    private final TetheringMetrics mTetheringMetrics;

    public RequestTracker(boolean useFuzzyMatching, TetheringMetrics metrics) {
        mUseFuzzyMatching = useFuzzyMatching;
        mTetheringMetrics = metrics;
    }

    private class PendingRequest {
        @NonNull
        private final TetheringRequest mTetheringRequest;
        @Nullable
        private IIntResultListener mListener;


        private PendingRequest(@NonNull TetheringRequest tetheringRequest,
                @Nullable IIntResultListener listener) {
            mTetheringRequest = tetheringRequest;
            mListener = listener;
        }

        @NonNull
        TetheringRequest getTetheringRequest() {
            return mTetheringRequest;
        }

        private void maybeSendPendingTetherResult(final int result) {
            if (mListener == null) return;

            try {
                mListener.onResult(result);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
            mListener = null;
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
         * Failure indicating that the request could not be added due to an existing request with
         * a pending listener.
         */
        FAILURE_EXISTING_REQUEST_WITH_PENDING_LISTENER,
        /**
         * Failure indicating that the request could not be added due to a fuzzy-matched request
         * already pending or serving.
         */
        FAILURE_EXISTING_FUZZY_MATCHED_REQUEST
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
    public AddResult addPendingRequestFuzzyMatched(@NonNull final TetheringRequest newRequest,
            @Nullable final IIntResultListener listener) {
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
        mPendingRequests.add(new PendingRequest(newRequest, listener));
        return AddResult.SUCCESS;
    }

    /**
     * Add a pending request and listener. The request should be added before asking the link layer
     * to start, and should be retrieved with {@link #getNextPendingRequest(int)} once the link
     * layer comes up. The result of the add operation will be returned as an AddResult code.
     */
    public AddResult addPendingRequest(@NonNull final TetheringRequest newRequest,
            @Nullable final IIntResultListener listener) {
        if (mUseFuzzyMatching) {
            return addPendingRequestFuzzyMatched(newRequest, listener);
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

            // Check if the pending request has already been sent it's tethering result. If not,
            // we must reject the new request to avoid removing the existing listener without
            // sending its result.
            // Note: This only applies to Bluetooth which doesn't send the result until after the
            //       PAN service connects
            // TODO: Should we fail the existing listener and keep the new one instead? This would
            //       functionally be the same as start/stop/start.
            if (request.mListener != null) {
                return AddResult.FAILURE_EXISTING_REQUEST_WITH_PENDING_LISTENER;
            }
        }

        // Remove the existing pending request of the same type. We already filter out for
        // conflicting parameters above, so these would have been equivalent anyway (except for
        // UID).
        // Note: We already reject the new request if the existing request has a pending listener,
        // so the TETHER_ERROR_INTERNAL_ERROR passed into here is ignored.
        removeAllPendingRequests(newRequest.getTetheringType(), TETHER_ERROR_INTERNAL_ERROR);
        mPendingRequests.add(new PendingRequest(newRequest, listener));
        return AddResult.SUCCESS;
    }

    /**
     * Utility to add pending requests while ignoring any existing pending/serving request. This
     * should only be used when re-adding serving requests to the pending list for restart
     * states (e.g. IP conflict, Bluetooth service disconnection, etc), which is necessary to
     * prevent other requesters from adding a pending request while the restarting request is
     * removed from the serving list.
     */
    public void forceAddPendingRequest(@NonNull final TetheringRequest newRequest) {
        mPendingRequests.add(new PendingRequest(newRequest, null));
    }

    /**
     * Gets the next pending TetheringRequest of a given type, or creates a placeholder request if
     * there are none. If there are multiple requests of the same type in the list, then the
     * first of these requests will be returned.
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
     * Handles the result of requesting the link layer to start/stop for the given TetheringRequest.
     * If the result is an error, the request will be removed from the pending list.
     */
    public void handleLinkLayerResult(boolean enable,
            @NonNull final TetheringRequest tetheringRequest, final int result) {
        // Don't send the result of a successful enable until we actually enable the IpServer
        // in tetherInternal.
        if (mUseFuzzyMatching && enable && result == TETHER_ERROR_NO_ERROR) {
            return;
        }

        // The result of Bluetooth tethering will be sent after the pan service connects.
        if (result == TETHER_ERROR_BLUETOOTH_SERVICE_PENDING) return;

        // Send the result to the pending request that matches the given TetheringRequest.
        for (PendingRequest pendingRequest : mPendingRequests) {
            if (pendingRequest.getTetheringRequest().equals(tetheringRequest)) {
                pendingRequest.maybeSendPendingTetherResult(result);
                break;
            }
        }

        // Remove the request if the result is an error.
        if (result != TETHER_ERROR_NO_ERROR) {
            removeAllPendingRequests(tetheringRequest.getTetheringType(), result);
        }
    }

    /**
     * Removes all pending requests of the given tethering type and sends a result if we haven't
     * sent a result to the listener yet.
     *
     * @param type Tethering type
     */
    public void removeAllPendingRequests(int type, final int result) {
        mPendingRequests.removeIf(r -> {
            if (r.getTetheringRequest().getTetheringType() != type) return false;
            r.maybeSendPendingTetherResult(result);
            return true;
        });
    }

    /**
     * Removes a pending request and sends a result if we haven't sent a result to the listener yet.
     */
    public void removePendingRequest(@NonNull final TetheringRequest request,
            final int result) {
        mPendingRequests.removeIf(r -> {
            if (!r.getTetheringRequest().fuzzyMatches(request)) return false;
            r.sendPendingTetherResult(result);
            return true;
        });
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
        allRequests.addAll(getServingTetheringRequests());
        for (TetheringRequest request : allRequests) {
            if (!request.fuzzyMatches(tetheringRequest)) continue;
            if (matchUid && tetheringRequest.getUid() != request.getUid()) continue;
            return request;
        }
        return null;
    }
}
