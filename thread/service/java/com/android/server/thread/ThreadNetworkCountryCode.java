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
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextParams;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.thread.IOperationReceiver;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActiveCountryCodeChangedCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.Keep;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.HandlerExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provide functions for making changes to Thread Network country code. This Country Code is from
 * telephony, WiFi, location or OEM configuration. This class sends Country Code to Thread Network
 * vendor via the HAL.
 */
public class ThreadNetworkCountryCode {
    private static final String TAG = "ThreadNetworkCountryCode";
    // To be used when there is no country code available.
    @VisibleForTesting public static final String DEFAULT_COUNTRY_CODE = "00";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String BOOT_DEFAULT_THREADNETWORK_COUNTRY_CODE =
            "ro.boot.threadnetworkcountrycode";

    // Wait 1 hour between updates
    private static final long TIME_BETWEEN_UPDATES_MS = 1000L * 60 * 60 * 1;
    // Minimum distance before an update is triggered, in meters. We don't need this to be too
    // exact because all we care about is what country the user is in.
    private static final float DISTANCE_BETWEEN_UPDATES_METERS = 5_000.0f;

    private static final String COUNTRY_CODE_SOURCE_DEFAULT = "Default";
    private static final String COUNTRY_CODE_SOURCE_LOCATION = "Location";
    private static final String COUNTRY_CODE_SOURCE_OEM = "Oem";
    private static final String COUNTRY_CODE_SOURCE_OVERRIDE = "Override";
    private static final String COUNTRY_CODE_SOURCE_TELEPHONY = "Telephony";
    private static final String COUNTRY_CODE_SOURCE_TELEPHONY_LAST = "TelephonyLast";
    private static final String COUNTRY_CODE_SOURCE_WIFI = "Wifi";

    private final Context mContext;
    private final Handler mHandler;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private final LocationManager mLocationManager;
    private final Geocoder mGeocoder;
    private final ThreadNetworkControllerService mThreadNetworkControllerService;
    private final Set<ChangeListener> mListeners = new ArraySet<>();

    private CountryCodeInfo mCountryCodeInfo = null;
    private Map<Integer, TelephonyCountryCodeSlotInfo> mTelephonyCountryCodeInfoPerSlot =
            new ArrayMap();
    private String mWifiCountryCode = null;
    private String mLocationCountryCode = null;
    private String mOverrideCountryCode = null;
    private String mWifiCountryTimestamp = null;
    private String mLocationCountryTimestamp = null;

    /** Container class to store country code per sim slot. */
    public static class TelephonyCountryCodeSlotInfo {
        public int slotIdx;
        public String countryCode;
        public String lastKnownCountryCode;
        public String timestamp;

        @Override
        public String toString() {
            return "TelephonyCountryCodeSlotInfo[ slotIdx: "
                    + slotIdx
                    + ", countryCode: "
                    + countryCode
                    + ", lastKnownCountryCode: "
                    + lastKnownCountryCode
                    + ", timestamp: "
                    + timestamp
                    + "]";
        }
    }

    public interface ChangeListener {
        /**
         * Called when country code set to native layer successful.
         *
         * @param newCountryCode - The new configured Thread country code.
         */
        void onUpdateCountryCodeSucceeded(@Nullable String newCountryCode);

        /**
         * Called when country code set to native layer failed.
         *
         * @param statusCode - Status of the ThreadNetworkCountryCode configuring the {@code
         *     newCountryCode}: - ERROR_UNAVAILABLE: The country code is not supported by the native
         *     layer. - ERROR_INTERNAL_ERROR: Failed to set the country code for unknown internal
         *     errors.
         * @param newCountryCode - The new configured Thread country code.
         */
        void onUpdateCountryCodeFailed(int statusCode, @Nullable String newCountryCode);
    }

    /** Container class to store Thread country code information. */
    private class CountryCodeInfo {
        public String mCountryCode = null;
        public String mCountryCodeSource = null;
        public String mCountryCodeUpdatedTimestamp = null;

        public CountryCodeInfo() {}

        public CountryCodeInfo(String countryCode, String countryCodeSource) {
            mCountryCode = countryCode;
            mCountryCodeSource = countryCodeSource;
            mCountryCodeUpdatedTimestamp = LocalDateTime.now().format(FORMATTER);
        }

        public boolean IsCountryCodeEmpty() {
            return mCountryCode == null;
        }

        public boolean IsCountryCodeMatch(CountryCodeInfo countryCodeInfo) {
            return Objects.equals(countryCodeInfo.mCountryCode, mCountryCode);
        }

        @Override
        public String toString() {
            return "CountryCodeInfo[mCountryCodeSource: "
                    + mCountryCodeSource
                    + ", mCountryCode: "
                    + mCountryCode
                    + ", mCountryCodeUpdatedTimestamp: "
                    + mCountryCodeUpdatedTimestamp
                    + "]";
        }
    }

    private boolean isLocationUseForCountryCodeEnabled() {
        // TODO: b/311324956 read the configuration from the overlay configuration.
        return true;
    }

    private Geocoder makeGeocoder() {
        return new Geocoder(mContext);
    }

    private boolean isGeocoderPresent() {
        return Geocoder.isPresent();
    }

    private boolean isValidCountryCode(String countryCode) {
        return countryCode != null
                && countryCode.length() == 2
                && countryCode.chars().allMatch(Character::isLetterOrDigit);
    }

    @Nullable
    private String getOemDefaultCountryCode() {
        String country = SystemProperties.get(BOOT_DEFAULT_THREADNETWORK_COUNTRY_CODE);
        return isValidCountryCode(country) ? country.toUpperCase(Locale.US) : null;
    }

    public ThreadNetworkCountryCode(
            Context context,
            ThreadNetworkControllerService threadNetworkControllerService,
            Looper looper) {
        mContext =
                context.createContext(new ContextParams.Builder().setAttributionTag(TAG).build());
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mLocationManager = mContext.getSystemService(LocationManager.class);
        mThreadNetworkControllerService = threadNetworkControllerService;
        mHandler = new Handler(looper);
        mCountryCodeInfo = new CountryCodeInfo();
        mGeocoder = makeGeocoder();
    }

    @VisibleForTesting
    public ThreadNetworkCountryCode(
            Context context,
            ThreadNetworkControllerService threadNetworkControllerService,
            Looper looper,
            Geocoder geocoder) {
        mContext =
                context.createContext(new ContextParams.Builder().setAttributionTag(TAG).build());
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mLocationManager = mContext.getSystemService(LocationManager.class);
        mThreadNetworkControllerService = threadNetworkControllerService;
        mHandler = new Handler(looper);
        mCountryCodeInfo = new CountryCodeInfo();
        mGeocoder = geocoder;
    }

    @Keep
    private class WifiCountryCodeCallback implements ActiveCountryCodeChangedCallback {
        public void onActiveCountryCodeChanged(@NonNull String countryCode) {
            setWifiCountryCode(countryCode);
        }

        public void onCountryCodeInactive() {
            setWifiCountryCode("");
        }
    }

    private void setCountryCodeFromGeocodingLocation(@Nullable Location location) {
        if (location == null) return;
        Geocoder.GeocodeListener geocodeListener =
                (List<Address> addresses) -> {
                    if (addresses != null && !addresses.isEmpty()) {
                        String countryCode = addresses.get(0).getCountryCode();
                        mHandler.post(() -> setLocationCountryCode(countryCode));
                    }
                };
        mGeocoder.getFromLocation(
                location.getLatitude(), location.getLongitude(), 1, geocodeListener);
    }

    /** Initialize the module. */
    public void initialize() {
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int slotIdx =
                                intent.getIntExtra(
                                        SubscriptionManager.EXTRA_SLOT_INDEX,
                                        SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                        String countryCode =
                                intent.getStringExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY);
                        String lastKnownCountryCode =
                                intent.getStringExtra(
                                        TelephonyManager.EXTRA_LAST_KNOWN_NETWORK_COUNTRY);
                        Log.d(TAG, "Country code changed to: " + countryCode);
                        setTelephonyCountryCodeAndLastKnownCountryCode(
                                slotIdx, countryCode, lastKnownCountryCode);
                    }
                },
                new IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED),
                null,
                mHandler);

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            mContext.getSystemService(WifiManager.class)
                    .registerActiveCountryCodeChangedCallback(
                            new HandlerExecutor(mHandler), new WifiCountryCodeCallback());
        }

        if (isLocationUseForCountryCodeEnabled() && isGeocoderPresent()) {
            mLocationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    TIME_BETWEEN_UPDATES_MS,
                    DISTANCE_BETWEEN_UPDATES_METERS,
                    location -> setCountryCodeFromGeocodingLocation(location));
        }

        Log.d(TAG, "Default country code from system property is " + getOemDefaultCountryCode());
        List<SubscriptionInfo> subscriptionInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionInfoList == null) return; // No sim

        for (SubscriptionInfo subscriptionInfo : subscriptionInfoList) {
            String countryCode;
            int slotIndex;

            slotIndex = subscriptionInfo.getSimSlotIndex();
            try {
                countryCode = mTelephonyManager.getNetworkCountryIso(slotIndex);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to get country code for slot id:" + slotIndex, e);
                continue;
            }
            setTelephonyCountryCodeAndLastKnownCountryCode(slotIndex, countryCode, null);
        }

        if (isLocationUseForCountryCodeEnabled() && isGeocoderPresent()) {
            setCountryCodeFromGeocodingLocation(
                    mLocationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER));
        }
        // Current Wifi country code update is sent immediately on registration.
    }

    /** Register Country code changed listener. */
    public void registerListener(@NonNull ChangeListener listener) {
        mListeners.add(listener);
    }

    /** Unregister Country code changed listener. */
    public void unregisterListener(@NonNull ChangeListener listener) {
        mListeners.remove(listener);
    }

    private void setTelephonyCountryCodeAndLastKnownCountryCode(
            int slotIdx, String countryCode, String lastKnownCountryCode) {
        Log.d(
                TAG,
                "Set telephony country code to: "
                        + countryCode
                        + ", last country code to: "
                        + lastKnownCountryCode
                        + " for slotIdx: "
                        + slotIdx);
        TelephonyCountryCodeSlotInfo telephonyCountryCodeInfoSlot =
                mTelephonyCountryCodeInfoPerSlot.computeIfAbsent(
                        slotIdx, k -> new TelephonyCountryCodeSlotInfo());
        telephonyCountryCodeInfoSlot.slotIdx = slotIdx;
        telephonyCountryCodeInfoSlot.timestamp = LocalDateTime.now().format(FORMATTER);
        // Empty country code.
        if (TextUtils.isEmpty(countryCode)) {
            Log.d(TAG, "Received empty telephony country code");
            telephonyCountryCodeInfoSlot.countryCode = null;
        } else {
            telephonyCountryCodeInfoSlot.countryCode = countryCode.toUpperCase(Locale.US);
        }
        if (TextUtils.isEmpty(lastKnownCountryCode)) {
            Log.d(TAG, "Received empty telephony last known country code");
            telephonyCountryCodeInfoSlot.lastKnownCountryCode = null;
        } else {
            telephonyCountryCodeInfoSlot.lastKnownCountryCode =
                    lastKnownCountryCode.toUpperCase(Locale.US);
        }

        setCountryCode(false);
    }

    private void setWifiCountryCode(String countryCode) {
        Log.d(TAG, "Set wifi country code to: " + countryCode);
        mWifiCountryTimestamp = LocalDateTime.now().format(FORMATTER);
        // Empty country code.
        if (TextUtils.isEmpty(countryCode) || TextUtils.equals(countryCode, DEFAULT_COUNTRY_CODE)) {
            Log.d(TAG, "Received empty wifi country code");
            mWifiCountryCode = null;
        } else {
            mWifiCountryCode = countryCode.toUpperCase(Locale.US);
        }

        setCountryCode(false);
    }

    private void setLocationCountryCode(String countryCode) {
        Log.d(TAG, "Set location country code to: " + countryCode);
        mLocationCountryTimestamp = LocalDateTime.now().format(FORMATTER);
        // Empty country code.
        if (TextUtils.isEmpty(countryCode) || TextUtils.equals(countryCode, DEFAULT_COUNTRY_CODE)) {
            Log.d(TAG, "Received empty location country code");
            mLocationCountryCode = null;
        } else {
            mLocationCountryCode = countryCode.toUpperCase(Locale.US);
        }

        setCountryCode(false);
    }

    /**
     * Priority order of country code sources (we stop at the first known country code source):
     *
     * <ul>
     *   <li>1. Override country code - Country code forced via shell command (local/automated
     *       testing)
     *   <li>2. Telephony country code - Current country code retrieved via cellular. If there are
     *       multiple SIM's, the country code chosen is non-deterministic if they return different
     *       codes.
     *   <li>3. Wifi country code - Current country code retrieved via wifi (via 80211.ad).
     *   <li>4. Last known telephony country code - Last known country code retrieved via cellular.
     *       If there are multiple SIM's, the country code chosen is non-deterministic if they
     *       return different codes.
     *   <li>5. Location Country code - Country code retrieved from LocationManager Fused location
     *       provider.
     *   <li>6. OEM default country code - If set by the OEM, then we default to this country code.
     * </ul>
     *
     * @return the selected country code information.
     */
    private CountryCodeInfo pickCountryCode() {
        if (mOverrideCountryCode != null) {
            return new CountryCodeInfo(mOverrideCountryCode, COUNTRY_CODE_SOURCE_OVERRIDE);
        }

        for (TelephonyCountryCodeSlotInfo telephonyCountryCodeInfoSlot :
                mTelephonyCountryCodeInfoPerSlot.values()) {
            if (telephonyCountryCodeInfoSlot.countryCode != null) {
                return new CountryCodeInfo(
                        telephonyCountryCodeInfoSlot.countryCode, COUNTRY_CODE_SOURCE_TELEPHONY);
            }
        }

        if (mWifiCountryCode != null) {
            return new CountryCodeInfo(mWifiCountryCode, COUNTRY_CODE_SOURCE_WIFI);
        }

        for (TelephonyCountryCodeSlotInfo telephonyCountryCodeInfoSlot :
                mTelephonyCountryCodeInfoPerSlot.values()) {
            if (telephonyCountryCodeInfoSlot.lastKnownCountryCode != null) {
                return new CountryCodeInfo(
                        telephonyCountryCodeInfoSlot.lastKnownCountryCode,
                        COUNTRY_CODE_SOURCE_TELEPHONY_LAST);
            }
        }

        if (mLocationCountryCode != null) {
            return new CountryCodeInfo(mLocationCountryCode, COUNTRY_CODE_SOURCE_LOCATION);
        }

        return new CountryCodeInfo(getOemDefaultCountryCode(), COUNTRY_CODE_SOURCE_OEM);
    }

    private void setCountryCodeSucceeded(@NonNull CountryCodeInfo countryCodeInfo) {
        mCountryCodeInfo = countryCodeInfo;

        for (ChangeListener listener : mListeners) {
            listener.onUpdateCountryCodeSucceeded(mCountryCodeInfo.mCountryCode);
        }
    }

    private void setCountryCodeFailed(int status, @NonNull CountryCodeInfo countryCodeInfo) {
        Log.e(TAG, "Failed to set country code: " + countryCodeInfo.mCountryCode);
        for (ChangeListener listener : mListeners) {
            listener.onUpdateCountryCodeFailed(status, countryCodeInfo.mCountryCode);
        }
    }

    private static final class OperationReceiverProxy extends IOperationReceiver.Stub {
        final CountryCodeInfo mCountryCodeInfo;
        final ThreadNetworkCountryCode mThreadNetworkCountryCode;

        OperationReceiverProxy(
                @NonNull ThreadNetworkCountryCode threadNetworkCountryCode,
                @NonNull CountryCodeInfo countryCodeInfo) {
            this.mThreadNetworkCountryCode = threadNetworkCountryCode;
            this.mCountryCodeInfo = countryCodeInfo;
        }

        @Override
        public void onSuccess() {
            this.mThreadNetworkCountryCode.setCountryCodeSucceeded(this.mCountryCodeInfo);
        }

        @Override
        public void onError(int error, String errorMessage) {
            this.mThreadNetworkCountryCode.setCountryCodeFailed(error, this.mCountryCodeInfo);
        }
    }

    /**
     * Update country code
     *
     * @param forceUpdate Force update the country code even if it was the same as previously cached
     *     value.
     */
    @VisibleForTesting
    public void setCountryCode(boolean forceUpdate) {
        CountryCodeInfo countryCodeInfo = pickCountryCode();

        if (countryCodeInfo.IsCountryCodeEmpty()) {
            Log.i(TAG, "No valid country code, reset to " + DEFAULT_COUNTRY_CODE);
            countryCodeInfo.mCountryCode = DEFAULT_COUNTRY_CODE;
            countryCodeInfo.mCountryCodeSource = COUNTRY_CODE_SOURCE_DEFAULT;
        }

        if (!forceUpdate && countryCodeInfo.IsCountryCodeMatch(mCountryCodeInfo)) {
            Log.i(TAG, "Ignoring already set country code: " + countryCodeInfo.mCountryCode);
            return;
        }

        Log.i(TAG, "Set country code: " + countryCodeInfo.toString());
        mThreadNetworkControllerService.setCountryCode(
                countryCodeInfo.mCountryCode.getBytes(StandardCharsets.UTF_8),
                new OperationReceiverProxy(this, countryCodeInfo));
    }

    /**
     * Get country code.
     *
     * @return the country code that was last configured in the ThreadNetworkCountryCode.
     */
    public String getCountryCode() {
        return mCountryCodeInfo.mCountryCode;
    }

    /**
     * Is this a valid country code
     *
     * @param countryCode A 2-Character alphanumeric country code.
     * @return true if the countryCode is valid, false otherwise.
     */
    public static boolean isValid(String countryCode) {
        return countryCode != null
                && countryCode.length() == 2
                && countryCode.chars().allMatch(Character::isLetterOrDigit)
                && !countryCode.equals(DEFAULT_COUNTRY_CODE);
    }

    /**
     * This call will override any existing country code. This is for test purpose only and we
     * should disallow any update from telephony in this mode.
     *
     * @param countryCode A 2-Character alphanumeric country code.
     */
    public synchronized void setOverrideCountryCode(String countryCode) {
        if (TextUtils.isEmpty(countryCode)) {
            Log.d(TAG, "Fail to override country code because the country code is empty");
            return;
        }
        mOverrideCountryCode = countryCode.toUpperCase(Locale.US);
        setCountryCode(true);
    }

    /**
     * This is for clearing the country code previously set through #setOverrideCountryCode() method
     */
    public synchronized void clearOverrideCountryCode() {
        mOverrideCountryCode = null;
        setCountryCode(true);
    }

    /** Method to dump the current state of this ThreadNetworkCountryCode object. */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("---- Dump of ThreadNetworkCountryCode ----");
        pw.println("DefaultCountryCode(system property): " + getOemDefaultCountryCode());
        pw.println("mOverrideCountryCode: " + mOverrideCountryCode);
        pw.println("mTelephonyCountryCodeInfoSlot: " + mTelephonyCountryCodeInfoPerSlot);
        pw.println("mWifiCountryCode: " + mWifiCountryCode);
        pw.println("mWifiCountryTimestamp: " + mWifiCountryTimestamp);
        pw.println("mLocationCountryCode: " + mLocationCountryCode);
        pw.println("mLocationCountryTimestamp: " + mLocationCountryTimestamp);
        pw.println("mCountryCodeInfo: " + mCountryCodeInfo.toString());
        pw.println("---- Dump of ThreadNetworkCountryCode ----");
    }
}
