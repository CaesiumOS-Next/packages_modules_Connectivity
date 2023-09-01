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

package com.android.threadnetwork.demoapp;

import static androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.threadnetwork.demoapp.concurrent.BackgroundExecutorProvider;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.io.CharStreams;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class ConnectivityToolsFragment extends Fragment {
    private static final String TAG = "ConnectivityTools";

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration UDP_TIMEOUT = Duration.ofSeconds(10L);
    private final ListeningScheduledExecutorService backgroundExecutor =
            BackgroundExecutorProvider.getBackgroundExecutor();
    private final ArrayList<String> serverIpCandidates = new ArrayList<>();
    private final ArrayList<String> serverPortCandidates = new ArrayList<>();
    private Executor mainExecutor;

    private ActivityResultLauncher<Intent> startVpnActivityResultLauncher;
    private ListenableFuture<String> pingFuture;
    private ListenableFuture<String> udpFuture;
    private ArrayAdapter<String> pingServerIpAdapter;
    private ArrayAdapter<String> udpServerIpAdapter;
    private ArrayAdapter<String> udpServerPortAdapter;

    private Network threadNetwork;
    private boolean bindThreadNetwork = false;

    private void subscribeToThreadNetwork() {
        ConnectivityManager cm = getActivity().getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        threadNetwork = network;
                    }

                    @Override
                    public void onLinkPropertiesChanged(
                            Network network, LinkProperties linkProperties) {
                    }

                    @Override
                    public void onLost(Network network) {
                        threadNetwork = network;
                    }
                },
                new Handler(Looper.myLooper()));
    }

    private static String getPingCommand(String serverIp) {
        try {
            InetAddress serverAddress = InetAddresses.forString(serverIp);
            return (serverAddress instanceof Inet6Address)
                    ? "/system/bin/ping6"
                    : "/system/bin/ping";
        } catch (IllegalArgumentException e) {
            // The ping command can handle the illegal argument and output error message
            return "/system/bin/ping6";
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connectivity_tools_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mainExecutor = ContextCompat.getMainExecutor(getActivity());

        subscribeToThreadNetwork();

        AutoCompleteTextView pingServerIpText = view.findViewById(R.id.ping_server_ip_address_text);
        pingServerIpAdapter =
                new ArrayAdapter<String>(
                        getActivity(), R.layout.list_server_ip_address_view, serverIpCandidates);
        pingServerIpText.setAdapter(pingServerIpAdapter);
        TextView pingOutputText = view.findViewById(R.id.ping_output_text);
        Button pingButton = view.findViewById(R.id.ping_button);

        pingButton.setOnClickListener(
                v -> {
                    if (pingFuture != null) {
                        pingFuture.cancel(/* mayInterruptIfRunning= */ true);
                        pingFuture = null;
                    }

                    String serverIp = pingServerIpText.getText().toString().strip();
                    updateServerIpCandidates(serverIp);
                    pingOutputText.setText("Sending ping message to " + serverIp);

                    pingFuture = sendPing(serverIp);
                    Futures.addCallback(
                            pingFuture,
                            new FutureCallback<String>() {
                                @Override
                                public void onSuccess(String result) {
                                    pingOutputText.append("\n" + result);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    if (t instanceof CancellationException) {
                                        // Ignore the cancellation error
                                        return;
                                    }
                                    pingOutputText.append("\nFailed: " + t.getMessage());
                                }
                            },
                            mainExecutor);
                });

        AutoCompleteTextView udpServerIpText = view.findViewById(R.id.udp_server_ip_address_text);
        udpServerIpAdapter =
                new ArrayAdapter<String>(
                        getActivity(), R.layout.list_server_ip_address_view, serverIpCandidates);
        udpServerIpText.setAdapter(udpServerIpAdapter);
        AutoCompleteTextView udpServerPortText = view.findViewById(R.id.udp_server_port_text);
        udpServerPortAdapter =
                new ArrayAdapter<String>(
                        getActivity(), R.layout.list_server_port_view, serverPortCandidates);
        udpServerPortText.setAdapter(udpServerPortAdapter);
        TextInputEditText udpMsgText = view.findViewById(R.id.udp_message_text);
        TextView udpOutputText = view.findViewById(R.id.udp_output_text);

        SwitchMaterial switchBindThreadNetwork = view.findViewById(R.id.switch_bind_thread_network);
        switchBindThreadNetwork.setChecked(bindThreadNetwork);
        switchBindThreadNetwork.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (isChecked) {
                        Log.i(TAG, "Binding to the Thread network");

                        if (threadNetwork == null) {
                            Log.e(TAG, "Thread network is not available");
                            Toast.makeText(getActivity().getApplicationContext(), "Thread network is not available", Toast.LENGTH_LONG);
                            switchBindThreadNetwork.setChecked(false);
                        } else {
                            bindThreadNetwork = true;
                        }
                    } else {
                        bindThreadNetwork = false;
                    }
                });

        Button sendUdpButton = view.findViewById(R.id.send_udp_button);
        sendUdpButton.setOnClickListener(
                v -> {
                    if (udpFuture != null) {
                        udpFuture.cancel(/* mayInterruptIfRunning= */ true);
                        udpFuture = null;
                    }

                    String serverIp = udpServerIpText.getText().toString().strip();
                    String serverPort = udpServerPortText.getText().toString().strip();
                    String udpMsg = udpMsgText.getText().toString().strip();
                    updateServerIpCandidates(serverIp);
                    updateServerPortCandidates(serverPort);
                    udpOutputText.setText(
                            String.format(
                                    "Sending UDP message \"%s\" to [%s]:%s",
                                    udpMsg, serverIp, serverPort));

                    udpFuture = sendUdpMessage(serverIp, serverPort, udpMsg);
                    Futures.addCallback(
                            udpFuture,
                            new FutureCallback<String>() {
                                @Override
                                public void onSuccess(String result) {
                                    udpOutputText.append("\n" + result);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    if (t instanceof CancellationException) {
                                        // Ignore the cancellation error
                                        return;
                                    }
                                    udpOutputText.append("\nFailed: " + t.getMessage());
                                }
                            },
                            mainExecutor);
                });

        startVpnActivityResultLauncher =
                registerForActivityResult(
                        new StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == Activity.RESULT_OK) {
                                getActivity()
                                        .startService(
                                                Ipv6VpnService.getServiceIntent(getActivity()));
                            }
                        });
        SwitchMaterial switchStartVpnService = view.findViewById(R.id.switch_start_vpn_service);
        switchStartVpnService.setChecked(Ipv6VpnService.isRunning());
        switchStartVpnService.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (isChecked) {
                        Log.i(TAG, "Turning on VPN");

                        Intent intent = VpnService.prepare(getActivity());
                        if (intent != null) {
                            startVpnActivityResultLauncher.launch(intent);
                        } else {
                            getActivity()
                                    .startService(Ipv6VpnService.getServiceIntent(getActivity()));
                        }
                    } else {
                        Log.i(TAG, "Turning off VPN");

                        getActivity().sendBroadcast(new Intent(Ipv6VpnService.BROADCAST_VPN_STOP));
                        // Somehow this doesn't stop the service by its onDestroy method.
                        // getActivity().stopService(Ipv6VpnService.getServiceIntent(getActivity()));
                    }
                });
    }

    private void updateServerIpCandidates(String newServerIp) {
        if (!serverIpCandidates.contains(newServerIp)) {
            serverIpCandidates.add(0, newServerIp);
            pingServerIpAdapter.notifyDataSetChanged();
            udpServerIpAdapter.notifyDataSetChanged();
        }
    }

    private void updateServerPortCandidates(String newServerPort) {
        if (!serverPortCandidates.contains(newServerPort)) {
            serverPortCandidates.add(0, newServerPort);
            udpServerPortAdapter.notifyDataSetChanged();
        }
    }

    private ListenableFuture<String> sendPing(String serverIp) {
        return FluentFuture.from(Futures.submit(() -> doSendPing(serverIp), backgroundExecutor))
                .withTimeout(PING_TIMEOUT.getSeconds(), TimeUnit.SECONDS, backgroundExecutor);
    }

    private String doSendPing(String serverIp) throws IOException {
        String pingCommand = getPingCommand(serverIp);
        Process process =
                new ProcessBuilder()
                        .command(pingCommand, "-c 1", serverIp)
                        .redirectErrorStream(true)
                        .start();

        return CharStreams.toString(new InputStreamReader(process.getInputStream()));
    }

    private ListenableFuture<String> sendUdpMessage(
            String serverIp, String serverPort, String msg) {
        return FluentFuture.from(
                        Futures.submit(
                                () -> doSendUdpMessage(serverIp, serverPort, msg),
                                backgroundExecutor))
                .withTimeout(UDP_TIMEOUT.getSeconds(), TimeUnit.SECONDS, backgroundExecutor);
    }

    private String doSendUdpMessage(String serverIp, String serverPort, String msg)
            throws IOException {
        SocketAddress serverAddr = new InetSocketAddress(serverIp, Integer.parseInt(serverPort));

        try (DatagramSocket socket = new DatagramSocket()) {
            if (bindThreadNetwork && threadNetwork != null) {
                threadNetwork.bindSocket(socket);
                Log.i(TAG, "Successfully bind the socket to the Thread network");
            }

            socket.connect(serverAddr);
            Log.d(TAG, "connected " + serverAddr);

            byte[] msgBytes = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(msgBytes, msgBytes.length);

            Log.d(TAG, String.format("Sending message to server %s: %s", serverAddr, msg));
            socket.send(packet);
            Log.d(TAG, "Send done");

            Log.d(TAG, "Waiting for server reply");
            socket.receive(packet);
            return new String(packet.getData(), packet.getOffset(), packet.getLength(), UTF_8);
        }
    }
}
