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

package com.android.server.ethernet;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.IEthernetManager;
import android.net.IEthernetServiceListener;
import android.net.ITetheredInterfaceCallback;
import android.net.IpConfiguration;
import android.net.NetworkStack;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.util.PrintWriterPrinter;

import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.BasicShellCommandHandler;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

class EthernetShellCommand extends BasicShellCommandHandler {
    private static final String TAG = "EthernetServiceShellCommand";
    private final EthernetServiceImpl mService;
    private final Context mContext;

    public EthernetShellCommand(EthernetServiceImpl service, Context context) {
        mService = Objects.requireNonNull(service);
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        final PrintWriter pw = getOutPrintWriter();
        final PrintWriter perr = getErrPrintWriter();
        // Treat no command as help command.
        if (cmd == null || cmd.equals("")) {
            cmd = "help";
        }

        switch (cmd) {
            case "help": {
                onHelp();
                return 0;
            }
            case "set-lost-neighbor-reset": {
                String ifaceName = getNextArgRequired();
                String reset = getNextArgRequired();
                if (reset.equals("true")) {
                    mService.updateLostNeighborSetting(ifaceName, true);
                } else if (reset.equals("false")) {
                    mService.updateLostNeighborSetting(ifaceName, false);
                } else {
                    perr.println("Error: setting must be either 'true' or 'false'");
                }
                return 0;
            }
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Ethernet Service commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-lost-neighbor-reset");
        pw.println("    enabled: reset interface when lose reachability to gateway.");
        pw.println("    disabled: ignore gateway unreachable event.");
    }
}
