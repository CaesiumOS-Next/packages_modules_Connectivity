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
import android.annotation.StringDef;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.thread.ThreadNetworkException;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActiveCountryCodeChangedCallback;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.os.SystemProperties;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.thread.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Provide functions for making changes to Thread Network country code. This Country Code is from
 * telephony, WiFi, location or OEM configuration. This class sends Country Code to Thread Network
 * native layer.
 *
 * <p>This class is thread-safe.
 */
public class ThreadNetworkCountryCode {
    private static final String TAG = "ThreadNetworkCountryCode";
    // To be used when there is no country code available.
    @VisibleForTesting public static final String DEFAULT_COUNTRY_CODE = "WW";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final String BOOT_DEFAULT_THREADNETWORK_COUNTRY_CODE =
            "ro.boot.threadnetworkcountrycode";

    // Wait 1 hour between updates.
    private static final long TIME_BETWEEN_UPDATES_MS = 1000L * 60 * 60 * 1;
    // Minimum distance before an update is triggered, in meters. We don't need this to be too
    // exact because all we care about is what country the user is in.
    private static final float DISTANCE_BETWEEN_UPDATES_METERS = 5_000.0f;

    /** List of country code sources. */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(
            prefix = "COUNTRY_CODE_SOURCE_",
            value = {
                COUNTRY_CODE_SOURCE_DEFAULT,
                COUNTRY_CODE_SOURCE_LOCATION,
                COUNTRY_CODE_SOURCE_OEM,
                COUNTRY_CODE_SOURCE_OVERRIDE,
                COUNTRY_CODE_SOURCE_TELEPHONY,
                COUNTRY_CODE_SOURCE_TELEPHONY_LAST,
                COUNTRY_CODE_SOURCE_WIFI,
            })
    private @interface CountryCodeSource {}

    private static final String COUNTRY_CODE_SOURCE_DEFAULT = "Default";
    private static final String COUNTRY_CODE_SOURCE_LOCATION = "Location";
    private static final String COUNTRY_CODE_SOURCE_OEM = "Oem";
    private static final String COUNTRY_CODE_SOURCE_OVERRIDE = "Override";
    private static final String COUNTRY_CODE_SOURCE_TELEPHONY = "Telephony";
    private static final String COUNTRY_CODE_SOURCE_TELEPHONY_LAST = "TelephonyLast";
    private static final String COUNTRY_CODE_SOURCE_WIFI = "Wifi";

    private final ThreadNetworkContext mContext;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private final LocationManager mLocationManager;
    private final WifiManager mWifiManager;
    private final Geocoder mGeocoder;
    private final boolean mGeocoderPresent;
    private final ThreadNetworkControllerService mThreadNetworkControllerService;
    private final Map<Integer, TelephonyCountryCodeSlotInfo> mTelephonyCountryCodeInfoPerSlot =
            new ArrayMap();

    private CountryCodeInfo mCountryCodeInfo;
    private String mWifiCountryCode;
    private String mLocationCountryCode;
    private String mOverrideCountryCode;
    private Instant mWifiCountryTimestamp;
    private Instant mLocationCountryTimestamp;

    /** Container class to store country code per sim slot. */
    public static final class TelephonyCountryCodeSlotInfo {
        public int slotIndex;
        public String countryCode;
        public String lastKnownCountryCode;
        public Instant timestamp;

        @Override
        public String toString() {
            return "TelephonyCountryCodeSlotInfo{ slotIndex: "
                    + slotIndex
                    + ", countryCode: "
                    + countryCode
                    + ", lastKnownCountryCode: "
                    + lastKnownCountryCode
                    + ", timestamp: "
                    + DATE_FORMATTER.format(timestamp)
                    + "}";
        }
    }

    /** Container class to store Thread country code information. */
    private static final class CountryCodeInfo {
        private String mCountryCode;
        @CountryCodeSource private String mCountryCodeSource;
        private final Instant mCountryCodeUpdatedTimestamp;

        public CountryCodeInfo() {
            mCountryCodeUpdatedTimestamp = Instant.now();
        }

        public CountryCodeInfo(String countryCode, @CountryCodeSource String countryCodeSource) {
            mCountryCode = countryCode;
            mCountryCodeSource = countryCodeSource;
            mCountryCodeUpdatedTimestamp = Instant.now();
        }

        public void resetToDefault() {
            mCountryCode = DEFAULT_COUNTRY_CODE;
            mCountryCodeSource = COUNTRY_CODE_SOURCE_DEFAULT;
        }

        public String getCountryCode() {
            return mCountryCode;
        }

        public boolean isCountryCodeEmpty() {
            return mCountryCode == null;
        }

        public boolean isCountryCodeMatch(CountryCodeInfo countryCodeInfo) {
            return Objects.equals(countryCodeInfo.mCountryCode, mCountryCode);
        }

        @Override
        public String toString() {
            return "CountryCodeInfo{ mCountryCode: "
                    + mCountryCode
                    + ", mCountryCodeSource: "
                    + mCountryCodeSource
                    + ", mCountryCodeUpdatedTimestamp: "
                    + DATE_FORMATTER.format(mCountryCodeUpdatedTimestamp)
                    + "}";
        }
    }

    private boolean isLocationUseForCountryCodeEnabled() {
        if (mContext.getResources() != null) {
            return mContext.getResources().getBoolean(R.bool.location_use_for_country_code_enabled);
        }

        return true;
    }

    @Nullable
    private static String getOemDefaultCountryCode() {
        String country = SystemProperties.get(BOOT_DEFAULT_THREADNETWORK_COUNTRY_CODE);
        return isValidCountryCode(country) ? country.toUpperCase(Locale.ROOT) : null;
    }

    public ThreadNetworkCountryCode(
            ThreadNetworkContext context,
            TelephonyManager telephonyManager,
            SubscriptionManager subscriptionManager,
            LocationManager locationManager,
            WifiManager wifiManager,
            ThreadNetworkControllerService threadNetworkControllerService,
            Geocoder geocoder,
            boolean geocoderPresent) {
        mContext = context;
        mTelephonyManager = telephonyManager;
        mSubscriptionManager = subscriptionManager;
        mLocationManager = locationManager;
        mWifiManager = wifiManager;
        mThreadNetworkControllerService = threadNetworkControllerService;
        mGeocoder = geocoder;
        mGeocoderPresent = geocoderPresent;
        mCountryCodeInfo = new CountryCodeInfo();
    }

    private class WifiCountryCodeCallback implements ActiveCountryCodeChangedCallback {
        @Override
        public void onActiveCountryCodeChanged(@NonNull String countryCode) {
            Log.d(TAG, "Wifi country code is changed to " + countryCode);
            setWifiCountryCode(countryCode);
        }

        @Override
        public void onCountryCodeInactive() {
            Log.d(TAG, "Wifi country code is inactived");
            setWifiCountryCode("");
        }
    }

    private void setCountryCodeFromGeocodingLocation(@Nullable Location location) {
        if (location == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Geocoder.GeocodeListener geocodeListener =
                    (List<Address> addresses) -> {
                        if (addresses != null && !addresses.isEmpty()) {
                            String countryCode = addresses.get(0).getCountryCode();
                            setLocationCountryCode(countryCode);
                        }
                    };
            mGeocoder.getFromLocation(
                    location.getLatitude(), location.getLongitude(), 1, geocodeListener);
        } else {
            Log.wtf(
                    TAG,
                    "Unexpected call to set country code from the Geocoding location, "
                            + "Thread code never runs under T or lower.");
        }
    }

    /**
     * Sets up this Country Code module to listener to Telephony, Location and Wi-Fi country code
     * changes.
     */
    public synchronized void initialize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BroadcastReceiver broadcastReceiver =
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            int slotIndex =
                                    intent.getIntExtra(
                                            SubscriptionManager.EXTRA_SLOT_INDEX,
                                            SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                            String lastKnownCountryCode = null;
                            String countryCode =
                                    intent.getStringExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY);

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                lastKnownCountryCode =
                                        intent.getStringExtra(
                                                TelephonyManager.EXTRA_LAST_KNOWN_NETWORK_COUNTRY);
                            }

                            Log.d(TAG, "Telephony country code is changed to " + countryCode);
                            setTelephonyCountryCodeAndLastKnownCountryCode(
                                    slotIndex, countryCode, lastKnownCountryCode);
                        }
                    };

            mContext.registerReceiver(
                    broadcastReceiver,
                    new IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED),
                    Context.RECEIVER_EXPORTED);
        } else {
            Log.wtf(
                    TAG,
                    "Unexpected call to register the telephony country code changed callback, "
                            + "Thread code never runs under T or lower.");
        }

        if (mWifiManager != null) {
            mWifiManager.registerActiveCountryCodeChangedCallback(
                    r -> r.run(), new WifiCountryCodeCallback());
        }

        if (mGeocoderPresent && isLocationUseForCountryCodeEnabled()) {
            mLocationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    TIME_BETWEEN_UPDATES_MS,
                    DISTANCE_BETWEEN_UPDATES_METERS,
                    location -> setCountryCodeFromGeocodingLocation(location));
        }

        Log.d(TAG, "Default country code from system property is " + getOemDefaultCountryCode());
        List<SubscriptionInfo> subscriptionInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionInfoList == null) {
            Log.d(TAG, "No sim card is found");
            return;
        }

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
            Log.d(TAG, "Telephony slot " + slotIndex + " country code is " + countryCode);
            setTelephonyCountryCodeAndLastKnownCountryCode(
                    slotIndex, countryCode, null /* lastKnownCountryCode */);
        }
    }

    private synchronized void setTelephonyCountryCodeAndLastKnownCountryCode(
            int slotIndex, String countryCode, String lastKnownCountryCode) {
        Log.d(
                TAG,
                "Set telephony country code to: "
                        + countryCode
                        + ", last country code to: "
                        + lastKnownCountryCode
                        + " for slotIndex: "
                        + slotIndex);
        TelephonyCountryCodeSlotInfo telephonyCountryCodeInfoSlot =
                mTelephonyCountryCodeInfoPerSlot.computeIfAbsent(
                        slotIndex, k -> new TelephonyCountryCodeSlotInfo());
        telephonyCountryCodeInfoSlot.slotIndex = slotIndex;
        telephonyCountryCodeInfoSlot.timestamp = Instant.now();

        if (isValidCountryCode(countryCode)) {
            telephonyCountryCodeInfoSlot.countryCode = countryCode.toUpperCase(Locale.US);
        } else {
            Log.d(TAG, "Telephony country code is invalid (slotIndex=" + slotIndex + ")");
            telephonyCountryCodeInfoSlot.countryCode = null;
        }

        if (isValidCountryCode(lastKnownCountryCode)) {
            telephonyCountryCodeInfoSlot.lastKnownCountryCode =
                    lastKnownCountryCode.toUpperCase(Locale.ROOT);
        } else {
            Log.d(TAG, "Last known telephony country code is invalid");
            telephonyCountryCodeInfoSlot.lastKnownCountryCode = null;
        }

        updateCountryCode(false /* forceUpdate */);
    }

    private void setWifiCountryCode(String countryCode) {
        Log.d(TAG, "Set wifi country code to: " + countryCode);
        mWifiCountryTimestamp = Instant.now();
        if (isValidCountryCode(countryCode)) {
            mWifiCountryCode = countryCode.toUpperCase(Locale.ROOT);
        } else {
            Log.d(TAG, "Received empty wifi country code");
            mWifiCountryCode = null;
        }

        updateCountryCode(false /* forceUpdate */);
    }

    private void setLocationCountryCode(String countryCode) {
        Log.d(TAG, "Set location country code to: " + countryCode);
        mLocationCountryTimestamp = Instant.now();
        if (isValidCountryCode(countryCode)) {
            mLocationCountryCode = countryCode.toUpperCase(Locale.ROOT);
        } else {
            Log.d(TAG, "Received empty location country code");
            mLocationCountryCode = null;
        }

        updateCountryCode(false /* forceUpdate */);
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
     *   <li>5. Location Country code - Country code retrieved from LocationManager passived
     *       location provider.
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

    private OutcomeReceiver<Void, ThreadNetworkException> newOutcomeReceiver(
            @NonNull ThreadNetworkCountryCode threadNetworkCountryCode,
            @NonNull CountryCodeInfo countryCodeInfo) {
        return new OutcomeReceiver<Void, ThreadNetworkException>() {
            @Override
            public void onResult(Void result) {
                threadNetworkCountryCode.mCountryCodeInfo = countryCodeInfo;
            }

            @Override
            public void onError(@NonNull ThreadNetworkException error) {
                Log.e(
                        TAG,
                        "Failed to set country code "
                                + countryCodeInfo.getCountryCode()
                                + ", error("
                                + error
                                + ")");
            }
        };
    }

    /**
     * Updates country code to the Thread native layer.
     *
     * @param forceUpdate Force update the country code even if it was the same as previously cached
     *     value.
     */
    @VisibleForTesting
    public synchronized void updateCountryCode(boolean forceUpdate) {
        CountryCodeInfo countryCodeInfo = pickCountryCode();

        if (countryCodeInfo.isCountryCodeEmpty()) {
            countryCodeInfo.resetToDefault();
            Log.i(TAG, "No valid country code, reset to " + countryCodeInfo.getCountryCode());
        }

        if (!forceUpdate && countryCodeInfo.isCountryCodeMatch(mCountryCodeInfo)) {
            Log.i(TAG, "Ignoring already set country code " + countryCodeInfo.getCountryCode());
            return;
        }

        Log.i(TAG, "Set country code: " + countryCodeInfo);
        mThreadNetworkControllerService.setCountryCode(
                countryCodeInfo.getCountryCode(), newOutcomeReceiver(this, countryCodeInfo));
    }

    /**
     * Gets country code.
     *
     * @return the country code that was last configured in the ThreadNetworkCountryCode.
     */
    @Nullable
    public synchronized String getCountryCode() {
        return mCountryCodeInfo.getCountryCode();
    }

    /**
     * Returns {@code true} if {@code countryCode} is a valid country code.
     *
     * <p>A country code is valid if it consists of 2 alphanumeric characters.
     */
    public static boolean isValidCountryCode(String countryCode) {
        return countryCode != null
                && countryCode.length() == 2
                && countryCode.chars().allMatch(Character::isLetter);
    }

    /**
     * Overrides any existing country code. This is for test purpose only and we should disallow any
     * update from telephony in this mode.
     *
     * @param countryCode A 2-Character alphanumeric country code (as defined in ISO 3166).
     * @throws IllegalArgumentException if {@code countryCode} is a valid country code.
     */
    public synchronized void setOverrideCountryCode(String countryCode) {
        if (!isValidCountryCode(countryCode)) {
            throw new IllegalArgumentException("The override country code is invalid");
        }

        mOverrideCountryCode = countryCode.toUpperCase(Locale.ROOT);
        updateCountryCode(true /* forceUpdate */);
    }

    /** Clears the country code previously set through #setOverrideCountryCode() method. */
    public synchronized void clearOverrideCountryCode() {
        mOverrideCountryCode = null;
        updateCountryCode(true /* forceUpdate */);
    }

    /** Dumps the current state of this ThreadNetworkCountryCode object. */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("---- Dump of ThreadNetworkCountryCode ----");
        pw.println("mOverrideCountryCode: " + mOverrideCountryCode);
        pw.println("mTelephonyCountryCodeInfoSlot: " + mTelephonyCountryCodeInfoPerSlot);
        pw.println("mWifiCountryCode: " + mWifiCountryCode);
        pw.println("mWifiCountryTimestamp: " + DATE_FORMATTER.format(mWifiCountryTimestamp));
        pw.println("isLocationUseForCountryCodeEnabled: " + isLocationUseForCountryCodeEnabled());
        pw.println("mLocationCountryCode: " + mLocationCountryCode);
        pw.println(
                "mLocationCountryTimestamp: " + DATE_FORMATTER.format(mLocationCountryTimestamp));
        pw.println("OemDefaultCountryCode: " + getOemDefaultCountryCode());
        pw.println("mCountryCodeInfo: " + mCountryCodeInfo);
        pw.println("---- Dump of ThreadNetworkCountryCode ----");
    }
}
