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

import static com.google.common.io.BaseEncoding.base16;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.BorderRouterConfiguration;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.ScheduleMigrationReceiver;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executor;

public final class ThreadNetworkSettingsFragment extends Fragment {
    private static final String TAG = "ThreadNetworkSettings";

    private ThreadNetworkController threadController;
    private TextView textState;
    private TextView textNetworkInfo;
    private SwitchMaterial switchEnableThread;
    private SwitchMaterial switchEnableBorderRouter;
    private Executor mainExecutor;

    private static final byte[] DEFAULT_ACTIVE_DATASET_TLVS =
            base16().lowerCase()
                    .decode(
                            "0e080000000000010000000300001235060004001fffe00208dae21bccb8c321c40708fdc376ead74396bb0510c52f56cd2d38a9eb7a716954f8efd939030f4f70656e5468726561642d646231390102db190410fcb737e6fd6bb1b0fed524a4496363110c0402a0f7f8");
    private final ActiveOperationalDataset defaultActiveDataset =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_ACTIVE_DATASET_TLVS);

    private static String deviceRoleToString(int deviceRole) {
        switch (deviceRole) {
            case ThreadNetworkController.DEVICE_ROLE_DISABLED:
                return "Disabled";
            case ThreadNetworkController.DEVICE_ROLE_DETACHED:
                return "Detached";
            case ThreadNetworkController.DEVICE_ROLE_CHILD:
                return "Child";
            case ThreadNetworkController.DEVICE_ROLE_ROUTER:
                return "Router";
            case ThreadNetworkController.DEVICE_ROLE_LEADER:
                return "Leader";
            default:
                return "Unknown";
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.thread_network_settings_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ConnectivityManager cm = getActivity().getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        updateNetworkInfo(network);
                    }

                    @Override
                    public void onLinkPropertiesChanged(
                            Network network, LinkProperties linkProperties) {
                        updateNetworkInfo(network);
                    }

                    @Override
                    public void onLost(Network network) {
                        updateNetworkInfo(network);
                    }
                },
                new Handler(Looper.myLooper()));

        mainExecutor = ContextCompat.getMainExecutor(getActivity());
        ThreadNetworkManager threadManager =
                getActivity().getSystemService(ThreadNetworkManager.class);
        if (threadManager != null) {
            threadController = threadManager.getAllThreadNetworkControllers().get(0);
            threadController.registerStateCallback(
                    mainExecutor,
                    new ThreadNetworkController.StateCallback() {
                        @Override
                        public void onDeviceRoleChanged(int deviceRole) {
                            updateState();
                        }

                        @Override
                        public void onPartitionIdChanged(long partitionId) {
                            updateState();
                        }
                    });
            threadController.registerOperationalDatasetCallback(
                    mainExecutor,
                    activeDataset -> {
                        updateState();
                    });
        }

        textState = (TextView) view.findViewById(R.id.text_state);
        textNetworkInfo = (TextView) view.findViewById(R.id.text_network_info);

        if (threadController == null) {
            textState.setText("Thread not supported!");
            return;
        }

        switchEnableThread = (SwitchMaterial) view.findViewById(R.id.switch_enable_thread);
        switchEnableThread.setChecked(threadController.isAttached());
        switchEnableThread.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (isChecked) {
                        threadController.attachOrForm(
                                defaultActiveDataset,
                                mainExecutor,
                                new OutcomeReceiver<Void, ThreadNetworkException>() {
                                    @Override
                                    public void onError(ThreadNetworkException error) {
                                        Log.e(
                                                TAG,
                                                "Failed to form network " + defaultActiveDataset,
                                                error);
                                    }

                                    @Override
                                    public void onResult(Void v) {
                                        Log.i(TAG, "Successfully attached");
                                    }
                                });
                    } else {
                        threadController.detach(
                                mainExecutor,
                                v -> {
                                    Log.i(TAG, "Successfully detached");
                                });
                    }
                });

        switchEnableBorderRouter =
                (SwitchMaterial) view.findViewById(R.id.switch_enable_border_router);

        if (threadController.isBorderRouterSupported()) {
            BorderRouterConfiguration brConfig = threadController.getBorderRouterConfiguration();
            switchEnableBorderRouter.setChecked(
                    brConfig != null && brConfig.isBorderRouterEnabled());
            switchEnableBorderRouter.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> {
                        BorderRouterConfiguration newBrConfig =
                                new BorderRouterConfiguration.Builder(isChecked).build();
                        threadController.setBorderRouterConfiguration(
                                newBrConfig,
                                mainExecutor,
                                new OutcomeReceiver<Void, ThreadNetworkException>() {
                                    @Override
                                    public void onError(ThreadNetworkException error) {
                                        Log.e(
                                                TAG,
                                                "Failed to set Border Router configuration to "
                                                        + newBrConfig,
                                                error);
                                    }

                                    @Override
                                    public void onResult(Void v) {
                                        Log.i(
                                                TAG,
                                                "Successfully set Border Router configuration to "
                                                        + newBrConfig);
                                    }
                                });
                    });
        } else {
            switchEnableBorderRouter.setChecked(false);
        }

        TextView migrateNetworkState = view.findViewById(R.id.text_migrate_network_state);
        ((Button) view.findViewById(R.id.button_migrate_network))
                .setOnClickListener(
                        v -> {
                            ActiveOperationalDataset activeDataset =
                                    new ActiveOperationalDataset.Builder(
                                                    ActiveOperationalDataset.createRandomDataset())
                                            .setActiveTimestamp(
                                                    OperationalDatasetTimestamp.fromInstant(
                                                            Instant.now()))
                                            .build();
                            PendingOperationalDataset pendingDataset =
                                    new PendingOperationalDataset(
                                            activeDataset,
                                            new OperationalDatasetTimestamp(1L, 0, false),
                                            Duration.ofSeconds(30));

                            migrateNetworkState.setText("");
                            threadController.scheduleMigration(
                                    pendingDataset,
                                    mainExecutor,
                                    new ScheduleMigrationReceiver() {

                                        @Override
                                        public void onScheduled(Duration delayTimer) {
                                            migrateNetworkState.setText(
                                                    String.format(
                                                            Locale.US,
                                                            "Migration is scheduled on the Leader."
                                                                    + " Will take effect in %ds",
                                                            delayTimer.toSeconds()));
                                        }

                                        @Override
                                        public void onMigrated() {
                                            migrateNetworkState.setText(
                                                    String.format(
                                                            Locale.US,
                                                            "This device has been migrated to"
                                                                    + " network: %s",
                                                            pendingDataset
                                                                    .getActiveOperationalDataset()));
                                        }

                                        @Override
                                        public void onError(ThreadNetworkException e) {
                                            migrateNetworkState.setText(
                                                    String.format(
                                                            Locale.US,
                                                            "Failed to schedule migration: %s",
                                                            e.getMessage()));
                                        }
                                    });
                        });

        updateState();
    }

    private void updateState() {
        Log.i(
                TAG,
                String.format(
                        "Updating Thread states (deviceRole: %s)",
                        deviceRoleToString(threadController.getDeviceRole())));

        ActiveOperationalDataset activeDataset = threadController.getActiveOperationalDataset();
        String state =
                String.format(
                        "Role            %s\n"
                                + "Partition ID    %d\n"
                                + "Network Name  %s\n"
                                + "Extended PAN ID  %s",
                        deviceRoleToString(threadController.getDeviceRole()),
                        threadController.getPartitionId(),
                        activeDataset != null ? activeDataset.getNetworkName() : null,
                        activeDataset != null
                                ? base16().encode(activeDataset.getExtendedPanId())
                                : null);
        textState.setText(state);
    }

    private void updateNetworkInfo(Network network) {
        ConnectivityManager cm = getActivity().getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) {
            textNetworkInfo.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder("Interface name:\n");
        sb.append(lp.getInterfaceName() + "\n");
        sb.append("Addresses:\n");
        for (LinkAddress la : lp.getLinkAddresses()) {
            sb.append(la + "\n");
        }
        sb.append("Routes:\n");
        for (RouteInfo route : lp.getRoutes()) {
            sb.append(route + "\n");
        }
        textNetworkInfo.setText(sb.toString());
    }
}
