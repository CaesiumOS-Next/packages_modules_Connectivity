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
import static android.net.TetheringManager.TETHER_ERROR_BLUETOOTH_SERVICE_PENDING;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;
import static android.net.TetheringManager.TetheringRequest.REQUEST_TYPE_EXPLICIT;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import android.annotation.IntDef;
import android.net.IIntResultListener;
import android.net.TetheringManager.TetheringRequest;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.networkstack.tethering.metrics.TetheringMetrics;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
 */
public class RequestTracker {
    private static final String TAG = RequestTracker.class.getSimpleName();

    @NonNull
    private final boolean mIsTetheringWithSoftApConfigEnabled;
    private final TetheringMetrics mTetheringMetrics;

    public RequestTracker(boolean isTetheringWithSoftApConfigEnabled, TetheringMetrics metrics) {
        mIsTetheringWithSoftApConfigEnabled = isTetheringWithSoftApConfigEnabled;
        mTetheringMetrics = metrics;
    }

    private class PendingRequest {
        @NonNull
        private final TetheringRequest mTetheringRequest;
        @Nullable
        private final IIntResultListener mListener;
        @Nullable
        private String mServingIface;

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

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ADD_RESULT_SUCCESS,
            ADD_RESULT_FAILURE_CONFLICTING_PENDING_REQUEST,
            ADD_RESULT_FAILURE_EXISTING_FUZZY_MATCHED_REQUEST,
    })
    private @interface AddResult {
    }

    public static final int ADD_RESULT_SUCCESS = 0;

    /**
     * Failure indicating that the request could not be added due to a request of the same type
     * with conflicting parameters already pending. If so, we must stop tethering for the
     * pending request before trying to add the result again.
     */
    public static final int ADD_RESULT_FAILURE_CONFLICTING_PENDING_REQUEST = 1;

    /**
     * Failure indicating that the request could not be added due to a fuzzy-matched request
     * already pending or serving.
     */
    public static final int ADD_RESULT_FAILURE_EXISTING_FUZZY_MATCHED_REQUEST = 2;

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
     * Add a pending request and listener. The request should be added before asking the link layer
     * to start, and should be retrieved with {@link #getNextPendingRequest(int)} once the link
     * layer comes up. The result of the add operation will be returned as an AddResult code.
     */
    @AddResult
    private int addPendingRequestLegacy(@NonNull final TetheringRequest newRequest,
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
                return ADD_RESULT_FAILURE_CONFLICTING_PENDING_REQUEST;
            }
        }

        // Remove the existing pending request of the same time. We already filter out for
        // conflicting parameters above, so these would have been equivalent anyway (except for
        // UID).
        // The only exception is bluetooth, which may have multiple pending listeners waiting
        // for the PAN service to connect.
        final int type = newRequest.getTetheringType();
        if (type != TETHERING_BLUETOOTH) {
            removePendingRequests(type, TETHER_ERROR_SERVICE_UNAVAIL);
        }
        mPendingRequests.add(new PendingRequest(newRequest, listener));
        return ADD_RESULT_SUCCESS;
    }

    /**
     * Add a pending request and listener. The request should be added before asking the link
     * layer to start, and should be retrieved with {@link #getNextPendingRequest(int)} once the link
     * layer comes up. The result of the add operation will be returned as an AddResult code.
     */
    @AddResult
    public int addPendingRequest(@NonNull final TetheringManager.TetheringRequest newRequest,
            @Nullable final IIntResultListener listener) {
        if (!mIsTetheringWithSoftApConfigEnabled) {
            return addPendingRequestLegacy(newRequest, listener);
        }

        // Can't add request if there's an existing request (pending or serving) that fuzzy
        // matches the new request.
        for (TetheringRequest servingRequest : mServingRequests.values()) {
            if (servingRequest.fuzzyMatches(newRequest)) {
                Log.i(TAG, "Cannot add pending request due to serving fuzzy-matched "
                        + "request: " + servingRequest);
                return ADD_RESULT_FAILURE_EXISTING_FUZZY_MATCHED_REQUEST;
            }
        }
        for (PendingRequest request : mPendingRequests) {
            TetheringRequest pendingRequest = request.getTetheringRequest();
            if (pendingRequest.fuzzyMatches(newRequest)) {
                Log.i(TAG, "Cannot add pending request due to pending fuzzy-matched "
                        + "request: " + pendingRequest);
                return ADD_RESULT_FAILURE_EXISTING_FUZZY_MATCHED_REQUEST;
            }
        }
        mPendingRequests.add(new PendingRequest(newRequest, listener));
        return ADD_RESULT_SUCCESS;
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
     * Sends the tether result from poking the link layer for the given tethering request and
     * remove the request if the result is a failure.
     * Note: For legacy code (mIsTetheringWithSoftApEnabled is false), there may be multiple
     *       duplicate pending requests with the exact same parameters (ignoring UID).
     *       Since these requests are considered equal, we will also remove the duplicates upon
     *       failure.
     */
    public void sendLinkLayerResult(boolean enable,
            @NonNull final TetheringRequest tetheringRequest, final int result) {
        // The result of Bluetooth tethering will be sent after the pan service connects.
        if (result == TETHER_ERROR_BLUETOOTH_SERVICE_PENDING) return;

        // Don't send the result of a successful enable until we actually enable the IpServer
        // in tetherInternal.
        if (mIsTetheringWithSoftApConfigEnabled && enable && result == TETHER_ERROR_NO_ERROR) {
            return;
        }

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
     * Removes all pending requests of the given tethering type and sends a result if we haven't
     * sent a result to the listener yet.
     *
     * @param type Tethering type
     */
    public void removeAllPendingRequests(int type, final int result) {
        mPendingRequests.removeIf(r -> {
            if (r.getTetheringRequest().getTetheringType() != type) return false;
            r.sendPendingTetherResult(result);
            return true;
        });
    }

    public void removePendingRequest(@NonNull final TetheringRequest request,
            final int result) {
        mPendingRequests.removeIf(r -> {
            if (!r.getTetheringRequest().fuzzyMatches(request)) return false;
            r.sendPendingTetherResult(result);
            return true;
        });
    }

    public void setServingRequest(@NonNull String iface,
            @NonNull final TetheringRequest tetheringRequest) {
        mServingRequests.put(iface, tetheringRequest);
    }

    @Nullable
    public TetheringRequest getServingRequest(@NonNull final String iface) {
        return mServingRequests.get(iface);
    }

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

    public boolean hasActiveExplicitRequest(final int type) {
        List<TetheringRequest> allRequests = new ArrayList<>();
        allRequests.addAll(getPendingTetheringRequests());
        allRequests.addAll(getServingTetheringRequests());
        for (TetheringRequest request : allRequests) {
            if (request.getTetheringType() == type
                    && request.getRequestType() == REQUEST_TYPE_EXPLICIT) {
                return true;
            }
        }
        return false;
    }
}
