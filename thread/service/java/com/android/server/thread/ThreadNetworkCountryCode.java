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
import android.annotation.TargetApi;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.thread.ThreadNetworkException;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Provide functions for making changes to Thread Network country code. This Country Code is from
 * location or OEM configuration. This class sends Country Code to Thread Network native layer.
 *
 * <p>This class is thread-safe.
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class ThreadNetworkCountryCode {
    private static final String TAG = "ThreadNetworkCountryCode";
    // To be used when there is no country code available.
    @VisibleForTesting public static final String DEFAULT_COUNTRY_CODE = "WW";

    // Used by OEM to set the default country code.
    @VisibleForTesting
    public static final String OEM_COUNTRY_CODE_PROPERITY = "ro.boot.threadnetworkcountrycode";

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
            })
    private @interface CountryCodeSource {}

    private static final String COUNTRY_CODE_SOURCE_DEFAULT = "Default";
    private static final String COUNTRY_CODE_SOURCE_LOCATION = "Location";
    private static final String COUNTRY_CODE_SOURCE_OEM = "Oem";
    private static final String COUNTRY_CODE_SOURCE_OVERRIDE = "Override";

    private final Context mContext;
    private final LocationManager mLocationManager;
    private final Geocoder mGeocoder;
    private final boolean mIsGeocoderPresent;
    private final ThreadNetworkControllerService mThreadNetworkControllerService;

    private CountryCodeInfo mCurrentCountryCodeInfo;
    private CountryCodeInfo mLocationCountryCodeInfo;
    private String mOverrideCountryCode;

    /** Container class to store Thread country code information. */
    private static final class CountryCodeInfo {
        private String mCountryCode;
        @CountryCodeSource private String mCountryCodeSource;
        private final Instant mCountryCodeUpdatedTimestamp;

        public CountryCodeInfo(
                @Nullable String countryCode,
                @CountryCodeSource String countryCodeSource,
                @NonNull Instant instant) {
            mCountryCode = countryCode;
            mCountryCodeSource = countryCodeSource;
            mCountryCodeUpdatedTimestamp = instant;
        }

        public CountryCodeInfo(
                @Nullable String countryCode, @CountryCodeSource String countryCodeSource) {
            this(countryCode, countryCodeSource, Instant.now());
        }

        public CountryCodeInfo() {
            this(null, null, Instant.now());
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
                    + mCountryCodeUpdatedTimestamp
                    + "}";
        }
    }

    private boolean isLocationUseForCountryCodeEnabled() {
        // TODO: b/311324956 read the configuration from the overlay configuration.
        return true;
    }

    @Nullable
    private static String getOemDefaultCountryCode() {
        String countryCode = SystemProperties.get(OEM_COUNTRY_CODE_PROPERITY);
        return isValidCountryCode(countryCode) ? countryCode : null;
    }

    public ThreadNetworkCountryCode(
            Context context,
            LocationManager locationManager,
            ThreadNetworkControllerService threadNetworkControllerService,
            Geocoder geocoder,
            boolean geocoderPresent) {
        mContext = context;
        mLocationManager = locationManager;
        mThreadNetworkControllerService = threadNetworkControllerService;
        mGeocoder = geocoder;
        mIsGeocoderPresent = geocoderPresent;
        mCurrentCountryCodeInfo = new CountryCodeInfo();
    }

    /** Sets up this Country Code module to listener to Location country code changes. */
    public synchronized void initialize() {
        registerGeocoderCountryCodeCallback();
        Log.d(TAG, "Default country code from system property is " + getOemDefaultCountryCode());
        updateCountryCode(false /* forceUpdate */);
    }

    private synchronized void registerGeocoderCountryCodeCallback() {
        if (mIsGeocoderPresent && isLocationUseForCountryCodeEnabled()) {
            mLocationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    TIME_BETWEEN_UPDATES_MS,
                    DISTANCE_BETWEEN_UPDATES_METERS,
                    location -> setCountryCodeFromGeocodingLocation(location));
        }
    }

    private synchronized void setCountryCodeFromGeocodingLocation(@Nullable Location location) {
        if (location == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.wtf(
                    TAG,
                    "Unexpected call to set country code from the Geocoding location, "
                            + "Thread code never runs under T or lower.");
            return;
        }

        Geocoder.GeocodeListener geocodeListener =
                (List<Address> addresses) -> {
                    if (addresses != null && !addresses.isEmpty()) {
                        String countryCode = addresses.get(0).getCountryCode();

                        if (isValidCountryCode(countryCode)) {
                            Log.d(TAG, "Set location country code to: " + countryCode);
                            mLocationCountryCodeInfo =
                                    new CountryCodeInfo(countryCode, COUNTRY_CODE_SOURCE_LOCATION);
                        } else {
                            Log.d(TAG, "Received invalid location country code");
                            mLocationCountryCodeInfo = null;
                        }

                        updateCountryCode(false /* forceUpdate */);
                    }
                };

        mGeocoder.getFromLocation(
                location.getLatitude(), location.getLongitude(), 1, geocodeListener);
    }

    /**
     * Priority order of country code sources (we stop at the first known country code source):
     *
     * <ul>
     *   <li>1. Override country code - Country code forced via shell command (local/automated
     *       testing)
     *   <li>2. Location Country code - Country code retrieved from LocationManager passive location
     *       provider.
     *   <li>3. OEM default country code - If set by the OEM, then we default to this country code.
     * </ul>
     *
     * @return the selected country code information.
     */
    private CountryCodeInfo pickCountryCode() {
        if (mOverrideCountryCode != null) {
            return new CountryCodeInfo(mOverrideCountryCode, COUNTRY_CODE_SOURCE_OVERRIDE);
        }

        if (mLocationCountryCodeInfo != null) {
            return mLocationCountryCodeInfo;
        }

        return new CountryCodeInfo(getOemDefaultCountryCode(), COUNTRY_CODE_SOURCE_OEM);
    }

    private OutcomeReceiver<Void, ThreadNetworkException> newOtStatusReceiver(
            @NonNull ThreadNetworkCountryCode threadNetworkCountryCode,
            @NonNull CountryCodeInfo countryCodeInfo) {
        return new OutcomeReceiver<Void, ThreadNetworkException>() {
            @Override
            public void onResult(Void result) {
                threadNetworkCountryCode.mCurrentCountryCodeInfo = countryCodeInfo;
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

        if (!forceUpdate && countryCodeInfo.isCountryCodeMatch(mCurrentCountryCodeInfo)) {
            Log.i(TAG, "Ignoring already set country code " + countryCodeInfo.getCountryCode());
            return;
        }

        Log.i(TAG, "Set country code: " + countryCodeInfo);
        mThreadNetworkControllerService.setCountryCode(
                countryCodeInfo.getCountryCode().toUpperCase(Locale.ROOT),
                newOtStatusReceiver(this, countryCodeInfo));
    }

    /**
     * Gets country code.
     *
     * @return the country code that was last configured in the ThreadNetworkCountryCode.
     */
    @Nullable
    public synchronized String getCountryCode() {
        return mCurrentCountryCodeInfo.getCountryCode();
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
     * Overrides any existing country code.
     *
     * @param countryCode A 2-Character alphanumeric country code (as defined in ISO 3166).
     * @throws IllegalArgumentException if {@code countryCode} is a valid country code.
     */
    @VisibleForTesting
    public synchronized void setOverrideCountryCode(String countryCode) {
        if (!isValidCountryCode(countryCode)) {
            throw new IllegalArgumentException("The override country code is invalid");
        }

        mOverrideCountryCode = countryCode.toUpperCase(Locale.ROOT);
        updateCountryCode(true /* forceUpdate */);
    }

    /** Clears the country code previously set through {@link #setOverrideCountryCode} method. */
    public synchronized void clearOverrideCountryCode() {
        mOverrideCountryCode = null;
        updateCountryCode(true /* forceUpdate */);
    }

    /** Dumps the current state of this ThreadNetworkCountryCode object. */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("---- Dump of ThreadNetworkCountryCode begin ----");
        pw.println("mOverrideCountryCode: " + mOverrideCountryCode);
        pw.println("mLocationCountryCodeInfo: " + mLocationCountryCodeInfo);
        pw.println("OemDefaultCountryCode: " + getOemDefaultCountryCode());
        pw.println("mCurrentCountryCodeInfo: " + mCurrentCountryCodeInfo);
        pw.println("---- Dump of ThreadNetworkCountryCode end ------");
    }
}
