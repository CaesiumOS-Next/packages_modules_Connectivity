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

package android.net.thread;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.net.thread.IThreadNetworkController;
import android.net.thread.IThreadNetworkManager;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class provides the primary API for managing app aspects of Thread network connectivity.
 *
 * @hide
 */
@SystemApi
@SystemService(ThreadNetworkManager.SERVICE_NAME)
public final class ThreadNetworkManager {
    // This value tracks {@link Context#THREAD_NETWORK_SERVICE}. This is needed because add the time
    // this service is created, we need to support both Android U and V but {@link
    // Context#THREAD_NETWORK_SERVICE} Is only available on the V branch.
    // TODO: Remove this constant when {@link Context#THREAD_NETWORK_SERVICE} is available on all
    //  branches.
    /** @hide */
    public static final String SERVICE_NAME = "thread_network";

    // This value tracks {@link PackageManager#FEATURE_THREAD_NETWORK}. This is needed because add
    // the time this service is created, we need to support both Android U and V but {@link
    // PackageManager#FEATURE_THREAD_NETWORK} Is only available on the V branch.
    // TODO: Remove this constant when {@link PackageManager#FEATURE_THREAD_NETWORK} is available
    //  on all branches.
    /** @hide */
    public static final String FEATURE_NAME = "android.hardware.thread_network";

    @NonNull private final IThreadNetworkManager mManagerService;
    @NonNull private final List<ThreadNetworkController> mControllerServices;

    /** Creates a new ThreadNetworkManager instance. @hide */
    public ThreadNetworkManager(@NonNull IThreadNetworkManager managerService) {
        this(requireNonNull(managerService, "managerService cannot be null"),
                makeControllers(managerService));
    }

    private static List<ThreadNetworkController> makeControllers(
            @NonNull IThreadNetworkManager managerService) {
        List<ThreadNetworkController> result = new ArrayList<>();
        List<IThreadNetworkController> controllerServices;

        try {
            controllerServices = managerService.getAllThreadNetworkControllers();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return result;
        }

        for (IThreadNetworkController controllerService: controllerServices) {
            result.add(new ThreadNetworkController(controllerService));
        }
        return result;
    }

    private ThreadNetworkManager(@NonNull IThreadNetworkManager managerService,
                                 @NonNull List<ThreadNetworkController> controllerServices) {
        this.mManagerService = managerService;
        this.mControllerServices = controllerServices;
    }

    /** Returns the {@link ThreadNetworkController} object of all Thread networks. */
    @NonNull
    public List<ThreadNetworkController> getAllThreadNetworkControllers() {
        return Collections.unmodifiableList(mControllerServices);
    }
}
