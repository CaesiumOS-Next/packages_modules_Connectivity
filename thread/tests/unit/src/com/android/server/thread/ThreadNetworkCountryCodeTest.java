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

import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.thread.ThreadNetworkException;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActiveCountryCodeChangedCallback;
import android.os.OutcomeReceiver;
import android.os.SystemProperties;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.connectivity.ConnectivityResources;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.stubbing.Answer;

import java.util.Iterator;
import java.util.List;

/** Unit tests for {@link ThreadNetworkCountryCode}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ThreadNetworkCountryCodeTest {
    private static final String TEST_COUNTRY_CODE_US = "US";
    private static final String TEST_COUNTRY_CODE_CN = "CN";
    private static final int TEST_SLOT_INDEX_0 = 0;
    private static final int TEST_SLOT_INDEX_1 = 1;

    @Mock Context mContext;
    @Mock TelephonyManager mTelephonyManager;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock LocationManager mLocationManager;
    @Mock Geocoder mGeocoder;
    @Mock WifiManager mWifiManager;
    @Mock ThreadNetworkControllerService mThreadNetworkControllerService;
    @Mock PackageManager mPackageManager;
    @Mock Resources mResources;
    @Mock ConnectivityResources mConnectivityResources;
    @Mock Location mLocation;
    @Mock List<SubscriptionInfo> mSubscriptionInfoList;
    @Mock SubscriptionInfo mSubscriptionInfo0;
    @Mock SubscriptionInfo mSubscriptionInfo1;

    private ThreadNetworkCountryCode mThreadNetworkCountryCode;
    private MockitoSession mStaticMockSession;
    private boolean mErrorSetCountryCode;

    @Captor private ArgumentCaptor<BroadcastReceiver> mTelephonyCountryCodeReceiverCaptor;
    @Captor private ArgumentCaptor<ActiveCountryCodeChangedCallback> mWifiCountryCodeReceiverCaptor;
    @Captor private ArgumentCaptor<LocationListener> mLocationListenerCaptor;
    @Captor private ArgumentCaptor<Geocoder.GeocodeListener> mGeocodeListenerCaptor;

    @Captor
    private ArgumentCaptor<OutcomeReceiver<Void, ThreadNetworkException>> mOutcomeReceiverCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mConnectivityResources.get()).thenReturn(mResources);
        when(mResources.getBoolean(anyInt())).thenReturn(true);

        when(mSubscriptionManager.getActiveSubscriptionInfoList())
                .thenReturn(mSubscriptionInfoList);
        Iterator<SubscriptionInfo> iteratorMock = mock(Iterator.class);
        when(mSubscriptionInfoList.size()).thenReturn(2);
        when(mSubscriptionInfoList.iterator()).thenReturn(iteratorMock);
        when(iteratorMock.hasNext()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(iteratorMock.next()).thenReturn(mSubscriptionInfo0).thenReturn(mSubscriptionInfo1);
        when(mSubscriptionInfo0.getSimSlotIndex()).thenReturn(TEST_SLOT_INDEX_0);
        when(mSubscriptionInfo1.getSimSlotIndex()).thenReturn(TEST_SLOT_INDEX_1);

        when(mLocation.getLatitude()).thenReturn(0.0);
        when(mLocation.getLongitude()).thenReturn(0.0);

        mStaticMockSession =
                ExtendedMockito.mockitoSession().mockStatic(SystemProperties.class).startMocking();

        Answer setCountryCodeCallback =
                invocation -> {
                    try {
                        Object[] args = invocation.getArguments();
                        OutcomeReceiver<Void, ThreadNetworkException> cb =
                                (OutcomeReceiver<Void, ThreadNetworkException>) args[1];

                        if (mErrorSetCountryCode) {
                            cb.onError(
                                    new ThreadNetworkException(
                                            ERROR_INTERNAL_ERROR, new String("Internal error")));
                        } else {
                            cb.onResult(null);
                        }

                    } catch (Exception e) {
                        // Nothing to do
                    }
                    return new Object();
                };

        doAnswer(setCountryCodeCallback)
                .when(mThreadNetworkControllerService)
                .setCountryCode(
                        any(),
                        ArgumentMatchers.<OutcomeReceiver<Void, ThreadNetworkException>>any());

        mThreadNetworkCountryCode =
                new ThreadNetworkCountryCode(
                        mContext,
                        mTelephonyManager,
                        mSubscriptionManager,
                        mLocationManager,
                        mWifiManager,
                        mThreadNetworkControllerService,
                        mConnectivityResources,
                        mGeocoder,
                        true);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void initialize_noCountryCodeSourceAvailable_defaultCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        assertThat(mThreadNetworkCountryCode.getCountryCode())
                .isEqualTo(ThreadNetworkCountryCode.DEFAULT_COUNTRY_CODE);
    }

    @Test
    public void initialize_telephonyCountryCodeSourceAvailable_telephonyCountryCodeIsUsed() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE_US);

        mThreadNetworkCountryCode.initialize();

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void initialize_telephonyCountryCodeThrowException_noCountryCodeIsUsed() {
        doThrow(new IllegalArgumentException())
                .when(mTelephonyManager)
                .getNetworkCountryIso(anyInt());

        mThreadNetworkCountryCode.initialize();

        verify(mThreadNetworkControllerService, never()).setCountryCode(any(), any());
    }

    @Test
    public void initialize_telephonyAndWifiNotAvailable_oemCountryCodeIsUsed() {
        when(SystemProperties.get(anyString())).thenReturn(TEST_COUNTRY_CODE_US);
        doThrow(new IllegalArgumentException())
                .when(mTelephonyManager)
                .getNetworkCountryIso(anyInt());

        mThreadNetworkCountryCode.initialize();
        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onCountryCodeInactive();

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void initialize_lastKnownTelephonyAvailable_lastKnownTelephonyCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onCountryCodeInactive();
        verify(mContext)
                .registerReceiver(
                        mTelephonyCountryCodeReceiverCaptor.capture(),
                        any(),
                        eq(Context.RECEIVER_EXPORTED));
        Intent intent =
                new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                        .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, "")
                        .putExtra(
                                TelephonyManager.EXTRA_LAST_KNOWN_NETWORK_COUNTRY,
                                TEST_COUNTRY_CODE_US)
                        .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_INDEX_0);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void initialize_locationUseIsDisabled_locationFunctionIsNotCalled() {
        when(mResources.getBoolean(anyInt())).thenReturn(false);

        mThreadNetworkCountryCode.initialize();

        verifyNoMoreInteractions(mGeocoder);
        verifyNoMoreInteractions(mLocationManager);
    }

    @Test
    public void telephonyCountryCodeIsChanged_defaultCtyCodeAvailable_telephonyCtyCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        verify(mContext)
                .registerReceiver(
                        mTelephonyCountryCodeReceiverCaptor.capture(),
                        any(),
                        eq(Context.RECEIVER_EXPORTED));
        Intent intent =
                new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                        .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_US)
                        .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_INDEX_0);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void wifiCountryCodeIsChanged_defaultCountryCodeAvailable_wifiCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void locationCountryCodeIsChanged_defaultCtyCodeAvailable_locationCtyCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();

        verify(mLocationManager)
                .requestLocationUpdates(
                        anyString(), anyLong(), anyFloat(), mLocationListenerCaptor.capture());
        mLocationListenerCaptor.getValue().onLocationChanged(mLocation);
        verify(mGeocoder)
                .getFromLocation(
                        anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        Address mockAddress = mock(Address.class);
        when(mockAddress.getCountryCode()).thenReturn(TEST_COUNTRY_CODE_US);
        List<Address> addresses = List.of(mockAddress);
        mGeocodeListenerCaptor.getValue().onGeocode(addresses);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void telephonyCtyCodeIsChanged_wifiAndLocationCtyCodeAvailable_telephonyCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();
        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);
        verify(mLocationManager)
                .requestLocationUpdates(
                        anyString(), anyLong(), anyFloat(), mLocationListenerCaptor.capture());
        mLocationListenerCaptor.getValue().onLocationChanged(mLocation);
        verify(mGeocoder)
                .getFromLocation(
                        anyDouble(), anyDouble(), anyInt(), mGeocodeListenerCaptor.capture());
        Address mockAddress = mock(Address.class);
        when(mockAddress.getCountryCode()).thenReturn(TEST_COUNTRY_CODE_US);
        List<Address> addresses = List.of(mockAddress);
        mGeocodeListenerCaptor.getValue().onGeocode(addresses);

        verify(mContext)
                .registerReceiver(
                        mTelephonyCountryCodeReceiverCaptor.capture(),
                        any(),
                        eq(Context.RECEIVER_EXPORTED));
        Intent intent =
                new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                        .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_CN)
                        .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_INDEX_0);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_CN);
    }

    @Test
    public void
            telephonyCountryCodeIsChanged_wifiCountryCodeAvailable_telephonyCountryCodeIsUsed() {
        mThreadNetworkCountryCode.initialize();
        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);
        verify(mContext)
                .registerReceiver(
                        mTelephonyCountryCodeReceiverCaptor.capture(),
                        any(),
                        eq(Context.RECEIVER_EXPORTED));
        Intent intent =
                new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                        .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_CN)
                        .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_INDEX_0);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);

        intent =
                new Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                        .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, TEST_COUNTRY_CODE_CN)
                        .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, TEST_SLOT_INDEX_1);
        mTelephonyCountryCodeReceiverCaptor.getValue().onReceive(mock(Context.class), intent);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_CN);
    }

    @Test
    public void updateCountryCode_noForceUpdateTelephonyCountryCode_noCountryCodeIsUpdated() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE_US);
        mThreadNetworkCountryCode.initialize();
        clearInvocations(mThreadNetworkControllerService);

        mThreadNetworkCountryCode.updateCountryCode(false /* forceUpdate */);

        verify(mThreadNetworkControllerService, never()).setCountryCode(any(), any());
    }

    @Test
    public void updateCountryCode_forceUpdateTelephonyCountryCode_countryCodeIsUpdated() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE_US);
        mThreadNetworkCountryCode.initialize();
        clearInvocations(mThreadNetworkControllerService);

        mThreadNetworkCountryCode.updateCountryCode(true /* forceUpdate */);

        verify(mThreadNetworkControllerService)
                .setCountryCode(eq(TEST_COUNTRY_CODE_US), mOutcomeReceiverCaptor.capture());
    }

    @Test
    public void setOverrideCountryCode_telephonyAndWifiCtyCodeAvailable_overrideCtyCodeIsUsed() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE_US);
        mThreadNetworkCountryCode.initialize();
        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);

        mThreadNetworkCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_CN);

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_CN);
    }

    @Test
    public void clearOverrideCountryCode_telephonyAndWifiCtyCodeAvailable_telephonyCtyCodeIsUsed() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE_US);
        mThreadNetworkCountryCode.initialize();
        verify(mWifiManager)
                .registerActiveCountryCodeChangedCallback(
                        any(), mWifiCountryCodeReceiverCaptor.capture());
        mWifiCountryCodeReceiverCaptor.getValue().onActiveCountryCodeChanged(TEST_COUNTRY_CODE_US);
        mThreadNetworkCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_CN);

        mThreadNetworkCountryCode.clearOverrideCountryCode();

        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }

    @Test
    public void setCountryCodeFailed_telephonyCountryCodeAvailable_countryCodeIsNotUpdated() {
        when(mTelephonyManager.getNetworkCountryIso(anyInt())).thenReturn(TEST_COUNTRY_CODE_US);
        mThreadNetworkCountryCode.initialize();

        mErrorSetCountryCode = true;
        mThreadNetworkCountryCode.setOverrideCountryCode(TEST_COUNTRY_CODE_CN);

        verify(mThreadNetworkControllerService)
                .setCountryCode(eq(TEST_COUNTRY_CODE_CN), mOutcomeReceiverCaptor.capture());
        assertThat(mThreadNetworkCountryCode.getCountryCode()).isEqualTo(TEST_COUNTRY_CODE_US);
    }
}
