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

import android.Manifest;
import android.annotation.NonNull;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Tracks the uid of all the default messaging application which are role_sms role and
 * satellite_communication permission complaint and requests ConnectivityService to create multi
 * layer request with satellite internet access support for the default message application.
 * @hide
 */
public class SatelliteAccessController {
    private static final String TAG = SatelliteAccessController.class.getSimpleName();
    private final Context mContext;
    private final Dependencies mDeps;
    private final DefaultMessageRoleListener mDefaultMessageRoleListener;
    private final Consumer<Set<Integer>> mCallback;
    private final Set<Integer> mSatelliteNetworkFallbackUidCache = new ArraySet<>();
    private final Handler mConnectivityServiceHandler;
    private final HashMap<Integer, Set<Integer>> mUserIDSatelliteNetworkFallbackCache =
            new HashMap<>();

    /**
     *  Monitor {@link android.app.role.OnRoleHoldersChangedListener#onRoleHoldersChanged(String,
     *  UserHandle)},
     *
     */
    private final class DefaultMessageRoleListener
            implements OnRoleHoldersChangedListener {
        @Override
        public void onRoleHoldersChanged(String role, UserHandle user) {
            if (RoleManager.ROLE_SMS.equals(role)) {
                Log.i(TAG, "ROLE_SMS Change detected ");
                onRoleSmsChanged(user);
            }
        }

        public void register() {
            try {
                mDeps.addOnRoleHoldersChangedListenerAsUser(
                        mConnectivityServiceHandler::post, this, UserHandle.ALL);
            } catch (RuntimeException e) {
                Log.wtf(TAG, "Could not register satellite controller listener due to " + e);
            }
        }
    }

    public SatelliteAccessController(@NonNull final Context c,
            Consumer<Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(c), callback, connectivityServiceInternalHandler);
    }

    public static class Dependencies {
        private final RoleManager mRoleManager;

        private Dependencies(Context context) {
            mRoleManager = context.getSystemService(RoleManager.class);
        }

        /** See {@link RoleManager#getRoleHolders(String)} */
        public List<String> getRoleHolders(String roleName) {
            return mRoleManager.getRoleHolders(roleName);
        }

        /** See {@link RoleManager#addOnRoleHoldersChangedListenerAsUser} */
        public void addOnRoleHoldersChangedListenerAsUser(@NonNull Executor executor,
                @NonNull OnRoleHoldersChangedListener listener, UserHandle user) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(executor, listener, user);
        }
    }

    @VisibleForTesting
    SatelliteAccessController(@NonNull final Context c, @NonNull final Dependencies deps,
            Consumer<Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mDefaultMessageRoleListener = new DefaultMessageRoleListener();
        mCallback = callback;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
    }

    private void updateSatelliteNetworkFallbackUidListCache(List<String> packageNames,
            @NonNull UserHandle userHandle) {
        PackageManager pm =
                mContext.createContextAsUser(userHandle, 0).getPackageManager();
        if (pm != null) {
            for (String packageName : packageNames) {
                // Check if SATELLITE_COMMUNICATION permission is enabled for default sms
                // application package before adding it part of satellite network fallback uid
                // cache list.
                if (isSatellitePermissionEnabled(pm, packageName)) {
                    mSatelliteNetworkFallbackUidCache.add(getUidForPackage(pm, packageName));
                }
            }
        } else {
            Log.wtf(TAG, "package manager found null");
        }
    }

    //Check if satellite communication is enabled for the package
    private boolean isSatellitePermissionEnabled(PackageManager packageManager,
            String packageName) {
        return packageManager.checkPermission(
                Manifest.permission.SATELLITE_COMMUNICATION, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int getUidForPackage(PackageManager packageManager, String pkgName) {
        if (pkgName == null) {
            return Process.INVALID_UID;
        }
        try {
            if (packageManager != null) {
                ApplicationInfo applicationInfo = packageManager.getApplicationInfo(pkgName, 0);
                if (applicationInfo != null) {
                    return applicationInfo.uid;
                }
            }
        } catch (PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "Unable to find uid for package: " + pkgName);
        }
        return Process.INVALID_UID;
    }

    //on Role sms change triggered by OnRoleHoldersChangedListener()
    private void onRoleSmsChanged(@NonNull UserHandle userHandle) {
        final int userId = userHandle.getIdentifier();
        final List<String> packageNames = getRoleSmsChangedPackageName();
        Set<Integer> currUserPreviousSatelliteFallbackUid = new ArraySet<>();

        // Store previous satellite fallback uid available
        if (mUserIDSatelliteNetworkFallbackCache.containsKey(userId)) {
            currUserPreviousSatelliteFallbackUid =
                    new ArraySet<>(mUserIDSatelliteNetworkFallbackCache.get(userId));
        }

        // On Role change listener, update the satellite network fallback uid cache list
        mSatelliteNetworkFallbackUidCache.clear();
        if (packageNames != null) {
            Log.i(TAG, "currentUser : role_sms_packages: " + userId + " : " + packageNames);
            updateSatelliteNetworkFallbackUidListCache(packageNames, userHandle);
            Log.i(TAG, "satellite_fallback_uid: " + mSatelliteNetworkFallbackUidCache);
        }

        // on Role change, update the multilayer request at ConnectivityService with updated
        // satellite network fallback uid cache list of multiple users as applicable and if changed
        // or to revoke for previous default sms app
        if (!mSatelliteNetworkFallbackUidCache.equals(currUserPreviousSatelliteFallbackUid)) {
            // Current user uids for satellite fallback
            mUserIDSatelliteNetworkFallbackCache.put(userId,
                    new ArraySet<>(mSatelliteNetworkFallbackUidCache));

            // Merge all uids of multiple users available
            Set<Integer> mergedSatelliteNetworkFallbackUidCache =
                    mUserIDSatelliteNetworkFallbackCache.values()
                    .stream()
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
            Log.i(TAG, "merged uid list for multi layer request : "
                    + mergedSatelliteNetworkFallbackUidCache);

            // trigger multiple layer request for satellite network fallback of multi user uids
            mCallback.accept(mergedSatelliteNetworkFallbackUidCache);
        }
    }

    private List<String> getRoleSmsChangedPackageName() {
        try {
            return mDeps.getRoleHolders(RoleManager.ROLE_SMS);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Could not get package name at role sms change update due to: " + e);
            return null;
        }
    }

    /** Register OnRoleHoldersChangedListener */
    public void start() {
        mConnectivityServiceHandler.post(() -> onRoleSmsChanged(UserHandle.ALL));
        mDefaultMessageRoleListener.register();
    }
}
