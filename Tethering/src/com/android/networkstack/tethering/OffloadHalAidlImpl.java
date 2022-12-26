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

package com.android.networkstack.tethering;

import static com.android.networkstack.tethering.OffloadHardwareInterface.OFFLOAD_HAL_VERSION_AIDL;
import static com.android.networkstack.tethering.OffloadHardwareInterface.OFFLOAD_HAL_VERSION_NONE;

import android.annotation.NonNull;
import android.hardware.tetheroffload.ForwardedStats;
import android.hardware.tetheroffload.IOffload;
import android.hardware.tetheroffload.ITetheringOffloadCallback;
import android.hardware.tetheroffload.NatTimeoutUpdate;
import android.hardware.tetheroffload.NetworkProtocol;
import android.hardware.tetheroffload.OffloadCallbackEvent;
import android.os.Handler;
import android.os.NativeHandle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.system.OsConstants;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.SharedLog;
import com.android.networkstack.tethering.OffloadHardwareInterface.OffloadHalCallback;

import java.util.ArrayList;

/**
 * The implementation of IOffloadHal which based on Stable AIDL interface
 */
public class OffloadHalAidlImpl implements IOffloadHal {
    private static final String TAG = OffloadHalAidlImpl.class.getSimpleName();
    private static final String HAL_INSTANCE_NAME = IOffload.DESCRIPTOR + "/default";

    private final Handler mHandler;
    private final SharedLog mLog;
    private IOffload mIOffload;
    private OffloadHalCallback mOffloadHalCallback;
    private TetheringOffloadCallback mTetheringOffloadCallback;

    public OffloadHalAidlImpl(@NonNull Handler handler, @NonNull SharedLog log) {
        mHandler = handler;
        mLog = log.forSubComponent(TAG);
    }

    /**
     * Wrapper functions created to be mockable in unit tests
     */
    @VisibleForTesting
    protected IOffload getOffloadMockable() {
        return IOffload.Stub.asInterface(
                ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));
    }

    /**
     * Initialize the Tetheroffload HAL. Provides bound netlink file descriptors for use in the
     * management process.
     */
    public boolean initOffload(@NonNull NativeHandle handle1, @NonNull NativeHandle handle2,
            @NonNull OffloadHalCallback callback) {
        mIOffload = getOffloadMockable();
        if (mIOffload == null) {
            mLog.e("Service Offload was not found.");
            return false;
        }

        final String methodStr = "initOffload";
        mOffloadHalCallback = callback;
        mTetheringOffloadCallback = new TetheringOffloadCallback(mHandler, mOffloadHalCallback,
                mLog);
        try {
            mIOffload.initOffload(
                    ParcelFileDescriptor.adoptFd(handle1.getFileDescriptor().getInt$()),
                    ParcelFileDescriptor.adoptFd(handle2.getFileDescriptor().getInt$()),
                    mTetheringOffloadCallback);
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /** Stop the Tetheroffload HAL. */
    public boolean stopOffload() {
        final String methodStr = "stopOffload";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.stopOffload();
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }

        mIOffload = null;
        mTetheringOffloadCallback = null;
        mOffloadHalCallback = null;
        return true;
    }

    /** Get HAL interface version number. */
    public int getVersion() {
        return mIOffload == null ? OFFLOAD_HAL_VERSION_NONE : OFFLOAD_HAL_VERSION_AIDL;
    }

    /** Get Tx/Rx usage from last query. */
    public OffloadHardwareInterface.ForwardedStats getForwardedStats(@NonNull String upstream) {
        ForwardedStats stats = new ForwardedStats();
        final String methodStr = "getForwardedStats";
        if (checkIOffloadAndLogFailure(methodStr)) {
            try {
                stats = mIOffload.getForwardedStats(upstream);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
        }
        return new OffloadHardwareInterface.ForwardedStats(stats.rxBytes, stats.txBytes);
    }

    /** Set local prefixes to offload management process. */
    public boolean setLocalPrefixes(@NonNull ArrayList<String> localPrefixes) {
        final String methodStr = "setLocalPrefixes";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.setLocalPrefixes(localPrefixes.toArray(new String[localPrefixes.size()]));
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /**
     * Set data limit value to offload management process.
     * Method setDataLimit is deprecated in AIDL, so call setDataWarningAndLimit instead,
     * with warningBytes set to its MAX_VALUE.
     */
    public boolean setDataLimit(@NonNull String iface, long limit) {
        final long warning = Long.MAX_VALUE;
        final String methodStr = "setDataWarningAndLimit";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.setDataWarningAndLimit(iface, warning, limit);
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /** Set data warning and limit value to offload management process. */
    public boolean setDataWarningAndLimit(@NonNull String iface, long warning, long limit) {
        final String methodStr = "setDataWarningAndLimit";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.setDataWarningAndLimit(iface, warning, limit);
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /** Set upstream parameters to offload management process. */
    public boolean setUpstreamParameters(@NonNull String iface, @NonNull String v4addr,
            @NonNull String v4gateway, @NonNull ArrayList<String> v6gws) {
        final String methodStr = "setUpstreamParameters";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.setUpstreamParameters(iface, v4addr, v4gateway,
                    v6gws.toArray(new String[v6gws.size()]));
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /** Add downstream prefix to offload management process. */
    public boolean addDownstream(@NonNull String ifname, @NonNull String prefix) {
        final String methodStr = "addDownstream";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.addDownstream(ifname, prefix);
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /** Remove downstream prefix from offload management process. */
    public boolean removeDownstream(@NonNull String ifname, @NonNull String prefix) {
        final String methodStr = "removeDownstream";
        if (!checkIOffloadAndLogFailure(methodStr)) {
            return false;
        }
        try {
            mIOffload.removeDownstream(ifname, prefix);
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
            return false;
        }
        return true;
    }

    /**
     * Get the HAL version supported.
     *
     * @return one of {@code OFFLOAD_HAL_VERSION_*} represents the HAL version, or
     *         {@link #OFFLOAD_HAL_VERSION_NONE} if failed.
     */
    public static int getServiceVersion() {
        // Service Manager API ServiceManager#isDeclared supported after T.
        if (!SdkLevel.isAtLeastT()) {
            return OFFLOAD_HAL_VERSION_NONE;
        }
        return ServiceManager.isDeclared(HAL_INSTANCE_NAME)
                ? OFFLOAD_HAL_VERSION_AIDL
                : OFFLOAD_HAL_VERSION_NONE;
    }

    /**
     * Returns false if mIOffload is null, and logs failure containing methodStr.
     */
    private boolean checkIOffloadAndLogFailure(final String methodStr) {
        if (mIOffload == null) {
            mLog.e("Can't call " + methodStr + ", IOffload is null");
            return false;
        }
        return true;
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        mLog.e("IOffload." + methodStr + " failed with remote exception: ", e);
    }

    private void handleServiceSpecificException(ServiceSpecificException e, String methodStr) {
        mLog.e("IOffload." + methodStr + " failed with service specific exception: ", e);
    }

    private static class TetheringOffloadCallback extends ITetheringOffloadCallback.Stub {
        public final Handler handler;
        public final OffloadHalCallback callback;
        public final SharedLog log;

        TetheringOffloadCallback(
                Handler h, OffloadHalCallback cb, SharedLog sharedLog) {
            handler = h;
            callback = cb;
            log = sharedLog;
        }

        private void handleOnEvent(int event) {
            switch (event) {
                case OffloadCallbackEvent.OFFLOAD_STARTED:
                    callback.onStarted();
                    break;
                case OffloadCallbackEvent.OFFLOAD_STOPPED_ERROR:
                    callback.onStoppedError();
                    break;
                case OffloadCallbackEvent.OFFLOAD_STOPPED_UNSUPPORTED:
                    callback.onStoppedUnsupported();
                    break;
                case OffloadCallbackEvent.OFFLOAD_SUPPORT_AVAILABLE:
                    callback.onSupportAvailable();
                    break;
                case OffloadCallbackEvent.OFFLOAD_STOPPED_LIMIT_REACHED:
                    callback.onStoppedLimitReached();
                    break;
                case OffloadCallbackEvent.OFFLOAD_WARNING_REACHED:
                    callback.onWarningReached();
                    break;
                default:
                    log.e("Unsupported OffloadCallbackEvent: " + event);
            }
        }

        @Override
        public void onEvent(int event) {
            handler.post(() -> {
                handleOnEvent(event);
            });
        }

        @Override
        public void updateTimeout(NatTimeoutUpdate params) {
            handler.post(() -> {
                callback.onNatTimeoutUpdate(
                        networkProtocolToOsConstant(params.proto),
                        params.src.addr, params.src.port,
                        params.dst.addr, params.dst.port);
            });
        }

        @Override
        public String getInterfaceHash() {
            return ITetheringOffloadCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return ITetheringOffloadCallback.VERSION;
        }
    }

    private static int networkProtocolToOsConstant(int proto) {
        switch (proto) {
            case NetworkProtocol.TCP: return OsConstants.IPPROTO_TCP;
            case NetworkProtocol.UDP: return OsConstants.IPPROTO_UDP;
            default:
                // The caller checks this value and will log an error. Just make
                // sure it won't collide with valid OsConstants.IPPROTO_* values.
                return -Math.abs(proto);
        }
    }
}
