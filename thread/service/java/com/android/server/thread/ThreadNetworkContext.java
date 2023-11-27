/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wrapper for context to override getResources method. Resources for thread mainline jar needs to
 * be fetched from the resources APK.
 */
public class ThreadNetworkContext extends ContextWrapper {
    private static final String TAG = "ThreadNetworkContext";
    /** Intent action that is used to identify ServiceThreadResources.apk */
    private static final String ACTION_RESOURCES_APK =
            "com.android.server.thread.intent.action.SERVICE_THREAD_RESOURCES_APK";

    private static final String APEX_NAME = "com.android.tethering";
    /**
     * The path where the Thread Network apex is mounted. Current value =
     * "/apex/com.android.tethering"
     */
    private static final String APEX_PATH = new File("/apex", APEX_NAME).getAbsolutePath();

    private String mThreadOverlayApkPkgName;
    private Resources mThreadResourcesFromApk;

    public ThreadNetworkContext(@NonNull Context contextBase) {
        super(contextBase);
    }

    /**
     * Returns true if the app is in the Thread Network apex, false otherwise. Checks if the app's
     * path starts with "/apex/com.android.tethering".
     */
    public static boolean isAppInThreadApex(ApplicationInfo appInfo) {
        return appInfo.sourceDir.startsWith(APEX_PATH);
    }

    /** Gets the package name of ServiceThreadResources.apk */
    public String getThreadOverlayApkPkgName() {
        if (mThreadOverlayApkPkgName != null) {
            return mThreadOverlayApkPkgName;
        }

        List<ResolveInfo> resolveInfos =
                getPackageManager()
                        .queryIntentActivities(
                                new Intent(ACTION_RESOURCES_APK),
                                PackageManager.MATCH_SYSTEM_ONLY
                                        | PackageManager.MATCH_DISABLED_COMPONENTS);

        // remove apps that don't live in the Thread apex
        resolveInfos.removeIf(info -> !isAppInThreadApex(info.activityInfo.applicationInfo));

        if (resolveInfos.isEmpty()) {
            // Resource APK not loaded yet, print a stack trace to see where this is called from
            Log.e(
                    TAG,
                    "Attempted to fetch resources before Thread Resources APK is loaded!",
                    new IllegalStateException());
            return null;
        }

        if (resolveInfos.size() > 1) {
            // multiple apps found, log a warning, but continue
            Log.w(
                    TAG,
                    "Found > 1 APK that can resolve Thread Resources APK intent: "
                            + resolveInfos.stream()
                                    .map(info -> info.activityInfo.applicationInfo.packageName)
                                    .collect(Collectors.joining(", ")));
        }

        // Assume the first ResolveInfo is the one we're looking for
        ResolveInfo info = resolveInfos.get(0);
        mThreadOverlayApkPkgName = info.activityInfo.applicationInfo.packageName;
        Log.i(TAG, "Found Thread Resources APK at: " + mThreadOverlayApkPkgName);
        int enabledState =
                getPackageManager().getApplicationEnabledSetting(mThreadOverlayApkPkgName);
        if (enabledState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            Log.w(
                    TAG,
                    "Thread resources APK disabled, state: "
                            + enabledState
                            + ". Trying to re-enable");
            getPackageManager()
                    .setApplicationEnabledSetting(
                            mThreadOverlayApkPkgName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            0);
        }
        return mThreadOverlayApkPkgName;
    }

    private Context getResourcesApkContext() {
        try {
            String packageName = getThreadOverlayApkPkgName();
            if (packageName != null) {
                return createPackageContext(packageName, 0);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.wtf(TAG, "Failed to load resources", e);
        }

        return null;
    }

    /** Gets resources held in the thread resources APK. */
    @Override
    public Resources getResources() {
        if (mThreadResourcesFromApk == null) {
            Context resourcesApkContext = getResourcesApkContext();
            if (resourcesApkContext != null) {
                mThreadResourcesFromApk = resourcesApkContext.getResources();
            }
        }
        return mThreadResourcesFromApk;
    }
}
