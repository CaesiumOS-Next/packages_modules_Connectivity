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
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import android.net.IIntResultListener;
import android.net.TetheringManager.TetheringRequest;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.networkstack.tethering.metrics.TetheringMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to keep track of tethering requests.
 * The intended usage of this class is
 * 1) Add a pending request with {@link #addPendingRequest(TetheringRequest, IIntResultListener)}
 *    before asking the link layer to start.
 * 2) When the link layer is up, use {@link #getOrCreatePendingRequest(int)} to get a request to
 *    start IP serving with.
 * 3) Remove all pending requests with {@link #removeAllPendingRequests(int)}.
 * Note: This class is not thread-safe.
 * TODO: Add support for multiple Bluetooth requests before the PAN service connects instead of
 *       using a separate mPendingPanRequestListeners.
 * TODO: Add support for fuzzy-matched requests.
 */
public class RequestTracker {
    private static final String TAG = RequestTracker.class.getSimpleName();

    private final TetheringMetrics mTetheringMetrics;

    public RequestTracker(TetheringMetrics metrics) {
        mTetheringMetrics = metrics;
    }

    private class PendingRequest {
        @NonNull
        private final TetheringRequest mTetheringRequest;
        @Nullable
        private final IIntResultListener mListener;


        private PendingRequest(@NonNull TetheringRequest tetheringRequest,
                @Nullable IIntResultListener listener) {
            mTetheringRequest = tetheringRequest;
            mListener = listener;
        }

        @NonNull
        TetheringRequest getTetheringRequest() {
            return mTetheringRequest;
        }

        private void sendPendingTetherResult(final int result) {
            if (mListener != null) {
                try {
                    mListener.onResult(result);
                } catch (RemoteException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
            if (result != TETHER_ERROR_NO_ERROR) {
                final int type = mTetheringRequest.getTetheringType();
                mTetheringMetrics.updateErrorCode(type, result);
                mTetheringMetrics.sendReport(type);
            }
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
        FAILURE_CONFLICTING_PENDING_REQUEST
    }

    /**
     * List of pending requests added by {@link #addPendingRequest(TetheringRequest)}. There can be
     * only one per type, since we remove every request of the same type when we add a request.
     */
    private final List<PendingRequest> mPendingRequests = new ArrayList<>();

    @VisibleForTesting
    List<TetheringRequest> getPendingTetheringRequests() {
        List<TetheringRequest> requests = new ArrayList<>();
        for (PendingRequest pendingRequest : mPendingRequests) {
            requests.add(pendingRequest.getTetheringRequest());
        }
        return requests;
    }

    /**
     * Add a pending request and listener. The request should be added before asking the link layer
     * to start, and should be retrieved with {@link #getNextPendingRequest(int)} once the link
     * layer comes up. The result of the add operation will be returned as an AddResult code.
     */
    public AddResult addPendingRequest(@NonNull final TetheringRequest newRequest,
            @Nullable final IIntResultListener listener) {
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
        mPendingRequests.add(new PendingRequest(newRequest, listener));
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
     * Sends the tether result from requesting the link layer to start/stop for the given tethering
     * request and remove the request if the result is a failure.
     */
    public void sendLinkLayerResult(
            @NonNull final TetheringRequest tetheringRequest, final int result) {
        // The result of Bluetooth tethering will be sent after the pan service connects.
        if (result == TETHER_ERROR_BLUETOOTH_SERVICE_PENDING) return;

        mPendingRequests.removeIf(request -> {
            TetheringRequest pendingRequest = request.getTetheringRequest();
            if (pendingRequest.getTetheringType() != tetheringRequest.getTetheringType()) {
                return false;
            }

            if (pendingRequest.equals(tetheringRequest)) {
                request.sendPendingTetherResult(result);
            }

            // Remove any other pending request of the same type to match historical behavior.
            // This only applies to IPv4 prefix conflict handling; usually the only existing
            // pending request should match exactly what we've passed into enableTetherInternal
            // via startTethering. However, IPv4 prefix conflict restart will bypass
            // startTethering and call enableTetherInternal with a placeholder request directly,
            // so if this fails then we may end up removing any existing pending request.
            return result != TETHER_ERROR_NO_ERROR;
        });
    }

    /**
     * Removes all pending requests of the given tethering type.
     *
     * @param type Tethering type
     */
    public void removeAllPendingRequests(int type) {
        mPendingRequests.removeIf(r -> r.getTetheringRequest().getTetheringType() == type);
    }
}
