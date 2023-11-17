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
import android.content.Context;
import android.os.Binder;
import android.os.Process;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Interprets and executes 'adb shell cmd thread_network [args]'.
 *
 * <p>To add new commands: - onCommand: Add a case "<command>" execute. Return a 0 if command
 * executed successfully. - onHelp: add a description string.
 *
 * <p>Permissions: currently root permission is required for some commands. Others will enforce the
 * corresponding API permissions.
 */
public class ThreadNetworkShellCommand extends BasicShellCommandHandler {
    private static final int CMD_TIMEOUT_MS = 10_000;
    private static final String TAG = "ThreadNetworkShellCommand";

    // These don't require root access.
    // However, these do perform permission checks in the corresponding
    // ThreadNetworkControllerService methods.
    private static final String[] NON_PRIVILEGED_COMMANDS = {
        "help", "get-country-code",
    };

    private final ThreadNetworkCountryCode mCountryCode;
    private final Context mContext;

    ThreadNetworkShellCommand(Context context, @NonNull ThreadNetworkService threadNetworkService) {
        mCountryCode = threadNetworkService.getThreadNetworkCountryCode();
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        // Treat no command as help command.
        if (cmd == null || cmd.equals("")) {
            cmd = "help";
        }

        // Explicit exclusion from root permission
        if (indexOf(NON_PRIVILEGED_COMMANDS, cmd) == -1) {
            final int uid = Binder.getCallingUid();
            if (uid != Process.ROOT_UID) {
                throw new SecurityException(
                        "Uid "
                                + uid
                                + " does not have access to "
                                + cmd
                                + " thread command "
                                + "(or such command doesn't exist)");
            }
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "force-country-code":
                    {
                        boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                        if (enabled) {
                            String countryCode = getNextArgRequired();
                            if (!ThreadNetworkCountryCode.isValid(countryCode)) {
                                pw.println(
                                        "Invalid argument: Country code must be a 2-Character"
                                                + " alphanumeric code. But got countryCode "
                                                + countryCode
                                                + " instead");
                                return -1;
                            }
                            mCountryCode.setOverrideCountryCode(countryCode);
                            pw.println("Set Thread Country Code: " + countryCode);
                        } else {
                            mCountryCode.clearOverrideCountryCode();
                        }
                        return 0;
                    }
                case "get-country-code":
                    pw.println("Thread Country Code = " + mCountryCode.getCountryCode());
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            pw.println("Invalid args for " + cmd + ": ");
            e.printStackTrace(pw);
            return -1;
        } catch (Exception e) {
            pw.println("Exception while executing ThreadNetworkShellCommand" + cmd + ": ");
            e.printStackTrace(pw);
            return -1;
        }
    }

    private int indexOf(@NonNull String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], value)) return i;
        }
        return -1;
    }

    private static boolean argTrueOrFalse(String arg, String trueString, String falseString) {
        if (trueString.equals(arg)) {
            return true;
        } else if (falseString.equals(arg)) {
            return false;
        } else {
            throw new IllegalArgumentException(
                    "Expected '"
                            + trueString
                            + "' or '"
                            + falseString
                            + "' as next arg but got '"
                            + arg
                            + "'");
        }
    }

    private boolean getNextArgRequiredTrueOrFalse(String trueString, String falseString)
            throws IllegalArgumentException {
        String nextArg = getNextArgRequired();
        return argTrueOrFalse(nextArg, trueString, falseString);
    }

    private void onHelpNonPrivileged(PrintWriter pw) {
        pw.println("  get-country-code");
        pw.println("    Gets country code as a two-letter string");
    }

    private void onHelpPrivileged(PrintWriter pw) {
        pw.println("  force-country-code enabled <two-letter code> | disabled ");
        pw.println("    Sets country code to <two-letter code> or left for normal value");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Thread network commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        onHelpNonPrivileged(pw);
        if (Binder.getCallingUid() == Process.ROOT_UID) {
            onHelpPrivileged(pw);
        }
        pw.println();
    }
}
