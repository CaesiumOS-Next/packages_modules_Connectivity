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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;

import com.android.server.ConnectivityService;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class SatelliteAccessController {
    private static final String TAG = CarrierPrivilegeAuthenticator.class.getSimpleName();
    private static final int EVENT_ROLE_SMS_CHANGED = 1;
    private static final boolean DBG = true;
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Handler mHandler;
    private final RoleManagerAdapter mRoleManager;
    private final DefaultMessageRoleListener  mDefaultMessageRoleListener;
    private final ConnectivityService mConnectivity;
    @NonNull
    private Set<Integer> mSatelliteRoleSmsEnabledUid = new ArraySet<>();
    private final HashMap<String, Integer> mSatelliteRoleSmsEnabledUid_1 = new HashMap<>();

    private final class DefaultMessageRoleListener
            implements OnRoleHoldersChangedListener {
        @Override
        public void onRoleHoldersChanged(String role, UserHandle user) {
            if (RoleManager.ROLE_SMS.equals(role)) {
                Log.i(TAG, "ROLE_SMS Change detected ");
                mHandler.sendEmptyMessage(EVENT_ROLE_SMS_CHANGED);
            }
        }

        public void register() {
            try {
                mRoleManager.addOnRoleHoldersChangedListenerAsUser(
                        mContext.getMainExecutor(), this, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                Log.i(TAG, "Could not register satellite controller listener due to " + e);
            }
        }

        public void unregister() {
            try {
                mRoleManager.removeOnRoleHoldersChangedListenerAsUser(this, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                Log.i(TAG,"Could not register satellite controller listener due to " + e);
            }
        }
    }

    private final class MyHandler extends Handler{
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.i(TAG,"handleMessage: " + msg);
            if (msg.what == EVENT_ROLE_SMS_CHANGED) {
                onRoleSmsChanged();
            } else {
                Log.i(TAG, "Unhandled event: " + msg);
            }
        }
    }

    public interface RoleManagerAdapter {
        /** See {@link RoleManager#getRoleHolders(String)} */
        List<String> getRoleHolders(String roleName);
        /** See {@link RoleManager#addOnRoleHoldersChangedListenerAsUser} */
        void addOnRoleHoldersChangedListenerAsUser(Executor executor,
                OnRoleHoldersChangedListener listener, UserHandle user);
        /** See {@link RoleManager#removeOnRoleHoldersChangedListenerAsUser} */
        void removeOnRoleHoldersChangedListenerAsUser(OnRoleHoldersChangedListener listener,
                UserHandle user);
    }

    private static class RoleManagerAdapterImpl implements RoleManagerAdapter {
        private final RoleManager mRoleManager;

        private RoleManagerAdapterImpl(Context context) {
            mRoleManager = context.getSystemService(RoleManager.class);
        }

        @Override
        public List<String> getRoleHolders(String roleName) {
            return mRoleManager.getRoleHolders(roleName);
        }

        @Override
        public void addOnRoleHoldersChangedListenerAsUser(Executor executor,
                OnRoleHoldersChangedListener listener, UserHandle user) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(executor, listener, user);
        }

        @Override
        public void removeOnRoleHoldersChangedListenerAsUser(OnRoleHoldersChangedListener listener,
                UserHandle user) {
            mRoleManager.removeOnRoleHoldersChangedListenerAsUser(listener, user);
        }
    }

    public SatelliteAccessController(@NonNull final Context c,
            @NonNull final TelephonyManager t) {
        this(c, new Dependencies(), new RoleManagerAdapterImpl(c), t);
    }

    public static class Dependencies {
        /**
         * Create a HandlerThread to use in CarrierPrivilegeAuthenticator.
         */
        public HandlerThread makeHandlerThread() {
            return new HandlerThread(TAG);
        }
    }

    public SatelliteAccessController(@NonNull final Context c,
            @NonNull final SatelliteAccessController.Dependencies deps,
            RoleManagerAdapter roleManager,
            @NonNull final TelephonyManager t) {
        mContext = c;
        mPackageManager= c.getPackageManager();
        mRoleManager = roleManager;
        mDefaultMessageRoleListener = new DefaultMessageRoleListener();
        mConnectivity = new ConnectivityService(mContext);

        final HandlerThread thread = deps.makeHandlerThread();
        thread.start();
        mHandler = new MyHandler(thread.getLooper());

        List<String> packageNames = mRoleManager.getRoleHolders(RoleManager.ROLE_SMS);
        if (packageNames != null) {
            UpdateSatelliteNetworkPreferredUids(packageNames);
        }
        mConnectivity.updateSatelliteNetworkPreferenceUids();
        //Register for OnRoleHoldersChangedListener
        mDefaultMessageRoleListener.register();
    }

    private void UpdateSatelliteNetworkPreferredUids(List<String> packageNames) {
        for (String packageName : packageNames) {
            // Check if SATELLITE_COMMUNICATION permission is enabled for default sms application
            if(isSatellitePermissionEnabled(packageName)) {
                mSatelliteRoleSmsEnabledUid.add(getUidForPackage(packageName));
            }
        }
    }

    private boolean isSatellitePermissionEnabled(String packageName) {
        return mPackageManager.checkPermission(
                "android.permission.SATELLITE_COMMUNICATION", packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isRoleSmsSupported(String packageName) {
        return mPackageManager.checkPermission(
                "android.permission.SEND_SMS", packageName)
                == PackageManager.PERMISSION_GRANTED
                && mPackageManager.checkPermission(
                        "android.permission.RECEIVE_SMS", packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    int getUidForPackage(String pkgName) {
        if (pkgName == null) {
            return Process.INVALID_UID;
        }
        try {
            if (mPackageManager != null) {
                ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(pkgName, 0);
                if (applicationInfo != null) {
                    return applicationInfo.uid;
                }
            }
        } catch (PackageManager.NameNotFoundException exception) {
            Log.i(TAG, "Unable to find uid for package: " + pkgName);
        }
        return Process.INVALID_UID;
    }

    private void onRoleSmsChanged() {
        final List<String> packageNames = getRoleSmsChangedPackageName();
        mSatelliteRoleSmsEnabledUid.clear();
        if(packageNames != null) {
            UpdateSatelliteNetworkPreferredUids(packageNames);
        }
        mConnectivity.updateSatelliteNetworkPreferenceUids();
        // TODO : Revoke the network request for the previous default sms app accordingly by
        // removing the multi layer request and marking only default network
    }

    private List<String> getRoleSmsChangedPackageName() {
        try {
            return mRoleManager.getRoleHolders(RoleManager.ROLE_SMS);
        } catch (RuntimeException e) {
            Log.i(TAG, "Could not get package name at role sms change update due to: " + e);
            return null;
        }
    }

    public Set<Integer> getSatelliteSmsPreferredUids() {
        return mSatelliteRoleSmsEnabledUid;
    }

}
