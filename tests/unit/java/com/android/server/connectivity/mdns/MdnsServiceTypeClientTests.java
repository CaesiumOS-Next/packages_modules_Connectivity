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

package com.android.server.connectivity.mdns;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;

import com.android.server.connectivity.mdns.MdnsServiceTypeClient.QueryTaskConfig;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Tests for {@link MdnsServiceTypeClient}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsServiceTypeClientTests {
    private static final String SERVICE_TYPE = "_googlecast._tcp.local";

    @Mock private MdnsServiceBrowserListener mListener1;
    @Mock private MdnsServiceBrowserListener mListener2;
    @Mock private MdnsPacketWriter mPacketWriter;
    @Mock private MdnsSocketClient mSocketClient;
    @Captor private ArgumentCaptor<MdnsServiceInfo> mServiceInfoCaptor;

    private final byte[] mBuffer = new byte[10];

    private DatagramPacket[] mExpectedPackets;
    private ScheduledFuture<?>[] mExpectedSendFutures;
    private FakeExecutor mCurrentThreadExecutor = new FakeExecutor();

    private MdnsServiceTypeClient mClient;

    @Before
    @SuppressWarnings("DoNotMock")
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        mExpectedPackets = new DatagramPacket[16];
        mExpectedSendFutures = new ScheduledFuture<?>[16];

        for (int i = 0; i < mExpectedSendFutures.length; ++i) {
            mExpectedPackets[i] = new DatagramPacket(mBuffer, 0, 5);
            mExpectedSendFutures[i] = Mockito.mock(ScheduledFuture.class);
        }
        when(mPacketWriter.getPacket(any(SocketAddress.class)))
                .thenReturn(mExpectedPackets[0])
                .thenReturn(mExpectedPackets[1])
                .thenReturn(mExpectedPackets[2])
                .thenReturn(mExpectedPackets[3])
                .thenReturn(mExpectedPackets[4])
                .thenReturn(mExpectedPackets[5])
                .thenReturn(mExpectedPackets[6])
                .thenReturn(mExpectedPackets[7])
                .thenReturn(mExpectedPackets[8])
                .thenReturn(mExpectedPackets[9])
                .thenReturn(mExpectedPackets[10])
                .thenReturn(mExpectedPackets[11])
                .thenReturn(mExpectedPackets[12])
                .thenReturn(mExpectedPackets[13])
                .thenReturn(mExpectedPackets[14])
                .thenReturn(mExpectedPackets[15]);

        mClient =
                new MdnsServiceTypeClient(SERVICE_TYPE, mSocketClient, mCurrentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mPacketWriter;
                    }
                };
    }

    @Test
    public void sendQueries_activeScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        mClient.startSendAndReceive(mListener1, searchOptions);

        // First burst, 3 queries.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                1, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Second burst will be sent after initialTimeBetweenBurstsMs, 3 queries.
        verifyAndSendQuery(
                3, MdnsConfigs.initialTimeBetweenBurstsMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                4, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                5, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Third burst will be sent after initialTimeBetweenBurstsMs * 2, 3 queries.
        verifyAndSendQuery(
                6, MdnsConfigs.initialTimeBetweenBurstsMs() * 2, /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                7, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                8, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Forth burst will be sent after initialTimeBetweenBurstsMs * 4, 3 queries.
        verifyAndSendQuery(
                9, MdnsConfigs.initialTimeBetweenBurstsMs() * 4, /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                10, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                11, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Fifth burst will be sent after timeBetweenBurstsMs, 3 queries.
        verifyAndSendQuery(12, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                13, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                14, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        mClient.stopSendAndReceive(mListener1);
        verify(mExpectedSendFutures[15]).cancel(true);
    }

    @Test
    public void sendQueries_reentry_activeScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        mClient.startSendAndReceive(mListener1, searchOptions);

        // First burst, first query is sent.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);

        // After the first query is sent, change the subtypes, and restart.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(false)
                        .build();
        mClient.startSendAndReceive(mListener1, searchOptions);
        // The previous scheduled task should be canceled.
        verify(mExpectedSendFutures[1]).cancel(true);

        // Queries should continue to be sent.
        verifyAndSendQuery(1, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                3, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        mClient.stopSendAndReceive(mListener1);
        verify(mExpectedSendFutures[5]).cancel(true);
    }

    @Test
    public void sendQueries_passiveScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        mClient.startSendAndReceive(mListener1, searchOptions);

        // First burst, 3 query.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                1, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Second burst will be sent after timeBetweenBurstsMs, 1 query.
        verifyAndSendQuery(3, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);
        // Third burst will be sent after timeBetweenBurstsMs, 1 query.
        verifyAndSendQuery(4, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);

        // Stop sending packets.
        mClient.stopSendAndReceive(mListener1);
        verify(mExpectedSendFutures[5]).cancel(true);
    }

    @Test
    public void sendQueries_reentry_passiveScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        mClient.startSendAndReceive(mListener1, searchOptions);

        // First burst, first query is sent.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);

        // After the first query is sent, change the subtypes, and restart.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(true)
                        .build();
        mClient.startSendAndReceive(mListener1, searchOptions);
        // The previous scheduled task should be canceled.
        verify(mExpectedSendFutures[1]).cancel(true);

        // Queries should continue to be sent.
        verifyAndSendQuery(1, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                3, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        mClient.stopSendAndReceive(mListener1);
        verify(mExpectedSendFutures[5]).cancel(true);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testQueryTaskConfig_alwaysAskForUnicastResponse() {
        //MdnsConfigsFlagsImpl.alwaysAskForUnicastResponseInEachBurst.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        QueryTaskConfig config =
                new QueryTaskConfig(searchOptions.getSubtypes(), searchOptions.isPassiveMode(), 1);

        // This is the first query. We will ask for unicast response.
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, 1);

        // For the rest of queries in this burst, we will NOT ask for unicast response.
        for (int i = 1; i < MdnsConfigs.queriesPerBurst(); i++) {
            int oldTransactionId = config.transactionId;
            config = config.getConfigForNextRun();
            assertFalse(config.expectUnicastResponse);
            assertEquals(config.subtypes, searchOptions.getSubtypes());
            assertEquals(config.transactionId, oldTransactionId + 1);
        }

        // This is the first query of a new burst. We will ask for unicast response.
        int oldTransactionId = config.transactionId;
        config = config.getConfigForNextRun();
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, oldTransactionId + 1);
    }

    @Test
    public void testQueryTaskConfig_askForUnicastInFirstQuery() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        QueryTaskConfig config =
                new QueryTaskConfig(searchOptions.getSubtypes(), searchOptions.isPassiveMode(), 1);

        // This is the first query. We will ask for unicast response.
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, 1);

        // For the rest of queries in this burst, we will NOT ask for unicast response.
        for (int i = 1; i < MdnsConfigs.queriesPerBurst(); i++) {
            int oldTransactionId = config.transactionId;
            config = config.getConfigForNextRun();
            assertFalse(config.expectUnicastResponse);
            assertEquals(config.subtypes, searchOptions.getSubtypes());
            assertEquals(config.transactionId, oldTransactionId + 1);
        }

        // This is the first query of a new burst. We will NOT ask for unicast response.
        int oldTransactionId = config.transactionId;
        config = config.getConfigForNextRun();
        assertFalse(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, oldTransactionId + 1);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testIfPreviousTaskIsCanceledWhenNewSessionStarts() {
        //MdnsConfigsFlagsImpl.useSessionIdToScheduleMdnsTask.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        mClient.startSendAndReceive(mListener1, searchOptions);
        Runnable firstMdnsTask = mCurrentThreadExecutor.getAndClearSubmittedRunnable();

        // Change the sutypes and start a new session.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(true)
                        .build();
        mClient.startSendAndReceive(mListener1, searchOptions);

        // Clear the scheduled runnable.
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the first mdns task is not successful canceled and it gets
        // executed anyway.
        firstMdnsTask.run();

        // Although it gets executes, no more task gets scheduled.
        assertNull(mCurrentThreadExecutor.getAndClearLastScheduledRunnable());
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testIfPreviousTaskIsCanceledWhenSessionStops() {
        //MdnsConfigsFlagsImpl.shouldCancelScanTaskWhenFutureIsNull.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        mClient.startSendAndReceive(mListener1, searchOptions);
        // Change the sutypes and start a new session.
        mClient.stopSendAndReceive(mListener1);
        // Clear the scheduled runnable.
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the first mdns task is not successful canceled and it gets
        // executed anyway.
        mCurrentThreadExecutor.getAndClearSubmittedRunnable().run();

        // Although it gets executes, no more task gets scheduled.
        assertNull(mCurrentThreadExecutor.getAndClearLastScheduledRunnable());
    }

    @Test
    public void processResponse_incompleteResponse() {
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());

        MdnsResponse response = mock(MdnsResponse.class);
        when(response.getServiceInstanceName()).thenReturn("service-instance-1");
        when(response.isComplete()).thenReturn(false);

        mClient.processResponse(response);

        verify(mListener1, never()).onServiceFound(any(MdnsServiceInfo.class));
        verify(mListener1, never()).onServiceUpdated(any(MdnsServiceInfo.class));
    }

    @Test
    public void processIPv4Response_completeResponseForNewServiceInstance() throws Exception {
        final String ipV4Address = "192.168.1.1";
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        "service-instance-1",
                        ipV4Address,
                        5353,
                        Collections.singletonList("ABCDE"),
                        Collections.emptyMap());
        mClient.processResponse(initialResponse);

        // Process a second response with a different port and updated text attributes.
        MdnsResponse secondResponse =
                createResponse(
                        "service-instance-1",
                        ipV4Address,
                        5354,
                        Collections.singletonList("ABCDE"),
                        Collections.singletonMap("key", "value"));
        mClient.processResponse(secondResponse);

        // Verify onServiceFound was called once for the initial response.
        verify(mListener1).onServiceFound(mServiceInfoCaptor.capture());
        MdnsServiceInfo initialServiceInfo = mServiceInfoCaptor.getAllValues().get(0);
        assertEquals(initialServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(initialServiceInfo.getIpv4Address(), ipV4Address);
        assertEquals(initialServiceInfo.getPort(), 5353);
        assertEquals(initialServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(initialServiceInfo.getAttributeByKey("key"));

        // Verify onServiceUpdated was called once for the second response.
        verify(mListener1).onServiceUpdated(mServiceInfoCaptor.capture());
        MdnsServiceInfo updatedServiceInfo = mServiceInfoCaptor.getAllValues().get(1);
        assertEquals(updatedServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(updatedServiceInfo.getIpv4Address(), ipV4Address);
        assertEquals(updatedServiceInfo.getPort(), 5354);
        assertTrue(updatedServiceInfo.hasSubtypes());
        assertEquals(updatedServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertEquals(updatedServiceInfo.getAttributeByKey("key"), "value");
    }

    @Test
    public void processIPv6Response_getCorrectServiceInfo() throws Exception {
        final String ipV6Address = "2000:3333::da6c:63ff:fe7c:7483";
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        "service-instance-1",
                        ipV6Address,
                        5353,
                        Collections.singletonList("ABCDE"),
                        Collections.emptyMap());
        mClient.processResponse(initialResponse);

        // Process a second response with a different port and updated text attributes.
        MdnsResponse secondResponse =
                createResponse(
                        "service-instance-1",
                        ipV6Address,
                        5354,
                        Collections.singletonList("ABCDE"),
                        Collections.singletonMap("key", "value"));
        mClient.processResponse(secondResponse);

        System.out.println("secondResponses ip"
                + secondResponse.getInet6AddressRecord().getInet6Address().getHostAddress());

        // Verify onServiceFound was called once for the initial response.
        verify(mListener1).onServiceFound(mServiceInfoCaptor.capture());
        MdnsServiceInfo initialServiceInfo = mServiceInfoCaptor.getAllValues().get(0);
        assertEquals(initialServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(initialServiceInfo.getIpv6Address(), ipV6Address);
        assertEquals(initialServiceInfo.getPort(), 5353);
        assertEquals(initialServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(initialServiceInfo.getAttributeByKey("key"));

        // Verify onServiceUpdated was called once for the second response.
        verify(mListener1).onServiceUpdated(mServiceInfoCaptor.capture());
        MdnsServiceInfo updatedServiceInfo = mServiceInfoCaptor.getAllValues().get(1);
        assertEquals(updatedServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(updatedServiceInfo.getIpv6Address(), ipV6Address);
        assertEquals(updatedServiceInfo.getPort(), 5354);
        assertTrue(updatedServiceInfo.hasSubtypes());
        assertEquals(updatedServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertEquals(updatedServiceInfo.getAttributeByKey("key"), "value");
    }

    @Test
    public void processResponse_goodBye() {
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());
        mClient.startSendAndReceive(mListener2, MdnsSearchOptions.getDefaultOptions());

        MdnsResponse response = mock(MdnsResponse.class);
        when(response.getServiceInstanceName()).thenReturn("goodbye-service-instance-name");
        when(response.isGoodbye()).thenReturn(true);
        mClient.processResponse(response);

        verify(mListener1).onServiceRemoved("goodbye-service-instance-name");
        verify(mListener2).onServiceRemoved("goodbye-service-instance-name");
    }

    @Test
    public void reportExistingServiceToNewlyRegisteredListeners() throws UnknownHostException {
        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        "service-instance-1",
                        "192.168.1.1",
                        5353,
                        Collections.singletonList("ABCDE"),
                        Collections.emptyMap());
        mClient.processResponse(initialResponse);

        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());

        // Verify onServiceFound was called once for the existing response.
        verify(mListener1).onServiceFound(mServiceInfoCaptor.capture());
        MdnsServiceInfo existingServiceInfo = mServiceInfoCaptor.getAllValues().get(0);
        assertEquals(existingServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(existingServiceInfo.getIpv4Address(), "192.168.1.1");
        assertEquals(existingServiceInfo.getPort(), 5353);
        assertEquals(existingServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(existingServiceInfo.getAttributeByKey("key"));

        // Process a goodbye message for the existing response.
        MdnsResponse goodByeResponse = mock(MdnsResponse.class);
        when(goodByeResponse.getServiceInstanceName()).thenReturn("service-instance-1");
        when(goodByeResponse.isGoodbye()).thenReturn(true);
        mClient.processResponse(goodByeResponse);

        mClient.startSendAndReceive(mListener2, MdnsSearchOptions.getDefaultOptions());

        // Verify onServiceFound was not called on the newly registered listener after the existing
        // response is gone.
        verify(mListener2, never()).onServiceFound(any(MdnsServiceInfo.class));
    }

    @Test
    public void processResponse_notAllowRemoveSearch_shouldNotRemove() throws Exception {
        final String serviceInstanceName = "service-instance-1";
        mClient.startSendAndReceive(
                mListener1,
                MdnsSearchOptions.newBuilder().build());
        Runnable firstMdnsTask = mCurrentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of());
        mClient.processResponse(initialResponse);

        // Clear the scheduled runnable.
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify onServiceRemoved was not called.
        verify(mListener1, never()).onServiceRemoved(serviceInstanceName);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void processResponse_allowSearchOptionsToRemoveExpiredService_shouldRemove()
            throws Exception {
        //MdnsConfigsFlagsImpl.allowSearchOptionsToRemoveExpiredService.override(true);
        final String serviceInstanceName = "service-instance-1";
        mClient =
                new MdnsServiceTypeClient(SERVICE_TYPE, mSocketClient, mCurrentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mPacketWriter;
                    }
                };
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = mCurrentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of());
        mClient.processResponse(initialResponse);

        // Clear the scheduled runnable.
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is under TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 1000);
        firstMdnsTask.run();

        // Verify onServiceRemoved was not called.
        verify(mListener1, never()).onServiceRemoved(serviceInstanceName);

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify onServiceRemoved was called.
        verify(mListener1, times(1)).onServiceRemoved(serviceInstanceName);
    }

    @Test
    public void processResponse_searchOptionsNotEnableServiceRemoval_shouldNotRemove()
            throws Exception {
        final String serviceInstanceName = "service-instance-1";
        mClient =
                new MdnsServiceTypeClient(SERVICE_TYPE, mSocketClient, mCurrentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mPacketWriter;
                    }
                };
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = mCurrentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of());
        mClient.processResponse(initialResponse);

        // Clear the scheduled runnable.
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify onServiceRemoved was not called.
        verify(mListener1, never()).onServiceRemoved(serviceInstanceName);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void processResponse_removeServiceAfterTtlExpiresEnabled_shouldRemove()
            throws Exception {
        //MdnsConfigsFlagsImpl.removeServiceAfterTtlExpires.override(true);
        final String serviceInstanceName = "service-instance-1";
        mClient =
                new MdnsServiceTypeClient(SERVICE_TYPE, mSocketClient, mCurrentThreadExecutor) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mPacketWriter;
                    }
                };
        mClient.startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = mCurrentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        MdnsResponse initialResponse =
                createResponse(
                        serviceInstanceName, "192.168.1.1", 5353, List.of("ABCDE"),
                        Map.of());
        mClient.processResponse(initialResponse);

        // Clear the scheduled runnable.
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        when(initialResponse.getServiceRecord().getRemainingTTL(anyLong())).thenReturn((long) 0);
        firstMdnsTask.run();

        // Verify onServiceRemoved was not called.
        verify(mListener1, times(1)).onServiceRemoved(serviceInstanceName);
    }

    // verifies that the right query was enqueued with the right delay, and send query by executing
    // the runnable.
    private void verifyAndSendQuery(int index, long timeInMs, boolean expectsUnicastResponse) {
        assertEquals(mCurrentThreadExecutor.getAndClearLastScheduledDelayInMs(), timeInMs);
        mCurrentThreadExecutor.getAndClearLastScheduledRunnable().run();
        if (expectsUnicastResponse) {
            verify(mSocketClient).sendUnicastPacket(mExpectedPackets[index]);
        } else {
            verify(mSocketClient).sendMulticastPacket(mExpectedPackets[index]);
        }
    }

    // A fake ScheduledExecutorService that keeps tracking the last scheduled Runnable and its delay
    // time.
    private class FakeExecutor extends ScheduledThreadPoolExecutor {
        private long mLastScheduledDelayInMs;
        private Runnable mLastScheduledRunnable;
        private Runnable mLastSubmittedRunnable;
        private int mFutureIndex;

        FakeExecutor() {
            super(1);
            mLastScheduledDelayInMs = -1;
        }

        @Override
        public Future<?> submit(Runnable command) {
            Future<?> future = super.submit(command);
            mLastSubmittedRunnable = command;
            return future;
        }

        // Don't call through the real implementation, just track the scheduled Runnable, and
        // returns a ScheduledFuture.
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            mLastScheduledDelayInMs = delay;
            mLastScheduledRunnable = command;
            return mExpectedSendFutures[mFutureIndex++];
        }

        // Returns the delay of the last scheduled task, and clear it.
        long getAndClearLastScheduledDelayInMs() {
            long val = mLastScheduledDelayInMs;
            mLastScheduledDelayInMs = -1;
            return val;
        }

        // Returns the last scheduled task, and clear it.
        Runnable getAndClearLastScheduledRunnable() {
            Runnable val = mLastScheduledRunnable;
            mLastScheduledRunnable = null;
            return val;
        }

        Runnable getAndClearSubmittedRunnable() {
            Runnable val = mLastSubmittedRunnable;
            mLastSubmittedRunnable = null;
            return val;
        }
    }

    // Creates a complete mDNS response.
    private MdnsResponse createResponse(
            @NonNull String serviceInstanceName,
            @NonNull String host,
            int port,
            @NonNull List<String> subtypes,
            @NonNull Map<String, String> textAttributes)
            throws UnknownHostException {
        String[] hostName = new String[]{"hostname"};
        MdnsServiceRecord serviceRecord = mock(MdnsServiceRecord.class);
        when(serviceRecord.getServiceHost()).thenReturn(hostName);
        when(serviceRecord.getServicePort()).thenReturn(port);

        MdnsResponse response = spy(new MdnsResponse(0));

        MdnsInetAddressRecord inetAddressRecord = mock(MdnsInetAddressRecord.class);
        if (host.contains(":")) {
            when(inetAddressRecord.getInet6Address())
                    .thenReturn((Inet6Address) Inet6Address.getByName(host));
            response.setInet6AddressRecord(inetAddressRecord);
        } else {
            when(inetAddressRecord.getInet4Address())
                    .thenReturn((Inet4Address) Inet4Address.getByName(host));
            response.setInet4AddressRecord(inetAddressRecord);
        }

        MdnsTextRecord textRecord = mock(MdnsTextRecord.class);
        List<String> textStrings = new ArrayList<>();
        for (Map.Entry<String, String> kv : textAttributes.entrySet()) {
            textStrings.add(kv.getKey() + "=" + kv.getValue());
        }
        when(textRecord.getStrings()).thenReturn(textStrings);

        response.setServiceRecord(serviceRecord);
        response.setTextRecord(textRecord);

        doReturn(false).when(response).isGoodbye();
        doReturn(true).when(response).isComplete();
        doReturn(serviceInstanceName).when(response).getServiceInstanceName();
        doReturn(new ArrayList<>(subtypes)).when(response).getSubtypes();
        return response;
    }
}
