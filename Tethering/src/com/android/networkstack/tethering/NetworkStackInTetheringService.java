package com.android.networkstack.tethering;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import androidx.annotation.Nullable;

import dalvik.system.PathClassLoader;

public class NetworkStackInTetheringService extends Service {
    private static final String TAG = NetworkStackInTetheringService.class.getSimpleName();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        final PathClassLoader pathClassLoader = new PathClassLoader(
                // "/apex/com.android.tethering/javalib/service-networkstack.jar",
                "/apex/com.android.tethering/javalib/service-connectivity.jar",
                // TODO: more generic library search path
                /*librarySearchPath=*/"/apex/com.android.tethering/lib64",
                getClass().getClassLoader());
        Log.i(TAG, "Registering NetworkStackConnector");
        try {
            final Class<?> nsService = Class.forName(
                    "android.net.connectivity.com.android.server.NetworkStackService", true, pathClassLoader);
            final IInterface connector = (IInterface) nsService.getDeclaredMethod(
                    "makeConnector", Context.class).invoke(null, getApplicationContext());
            Log.i(TAG, "NetworkStackConnector created");
            return connector.asBinder();
        } catch (ReflectiveOperationException e) {
            Log.wtf(TAG, "Could not create the NetworkStackConnector", e);
            // Fall through
            return null;
        }
    }
}
