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

package com.android.server;

import android.util.Log;

import com.android.modules.utils.build.SdkLevel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BpfRcUtils is responsible for comparing the bpf loader rc file.
 *
 * {@hide}
 */
public class BpfLoaderRcUtils {
    public static final String TAG = BpfLoaderRcUtils.class.getSimpleName();

    private static final List<String> BPF_LOADER_RC_S = List.of(
            "on load_bpf_programs",
            "write /proc/sys/net/core/bpf_jit_enable 1",
            "write /proc/sys/net/core/bpf_jit_kallsyms 1",
            "exec_start bpfloader",
            "service bpfloader /system/bin/bpfloader",
            "capabilities CHOWN SYS_ADMIN NET_ADMIN",
            "rlimit memlock 1073741824 1073741824",
            "oneshot",
            "reboot_on_failure reboot,bpfloader-failed",
            "updatable"
    );

    private static final List<String> BPF_LOADER_RC_T = List.of(
            "on load_bpf_programs",
            "write /proc/sys/kernel/unprivileged_bpf_disabled 0",
            "write /proc/sys/net/core/bpf_jit_enable 1",
            "write /proc/sys/net/core/bpf_jit_kallsyms 1",
            "exec_start bpfloader",
            "service bpfloader /system/bin/bpfloader",
            "capabilities CHOWN SYS_ADMIN NET_ADMIN",
            "rlimit memlock 1073741824 1073741824",
            "oneshot",
            "reboot_on_failure reboot,bpfloader-failed",
            "updatable"
    );

    private static final List<String> BPF_LOADER_RC_U = List.of(
            "on load_bpf_programs",
            "exec_start bpfloader",
            "service bpfloader /system/bin/bpfloader",
            "capabilities CHOWN SYS_ADMIN NET_ADMIN",
            "group root graphics network_stack net_admin net_bw_acct net_bw_stats net_raw system",
            "user root",
            "rlimit memlock 1073741824 1073741824",
            "oneshot",
            "reboot_on_failure reboot,bpfloader-failed",
            "updatable"
    );

    private static final List<String> BPF_LOADER_RC_UQPR2 = List.of(
            "on load_bpf_programs",
            "exec_start bpfloader",
            "service bpfloader /system/bin/netbpfload",
            "capabilities CHOWN SYS_ADMIN NET_ADMIN",
            "group root graphics network_stack net_admin net_bw_acct net_bw_stats net_raw system",
            "user root",
            "rlimit memlock 1073741824 1073741824",
            "oneshot",
            "reboot_on_failure reboot,bpfloader-failed",
            "updatable"
    );

    private static final List<List<String>> BPF_RC_FILE_TEMPLATES = List.of(BPF_LOADER_RC_S,
            BPF_LOADER_RC_T, BPF_LOADER_RC_U, BPF_LOADER_RC_UQPR2);

    private static final String BPF_LOADER_RC_FILE_PATH = "/etc/init/bpfloader.rc";
    private static final String NET_BPF_LOAD_RC_FILE_PATH = "/etc/init/netbpfload.rc";

    private BpfLoaderRcUtils() {
    }

    private static List<String> loadExistingBpfRcFile() {
        final List<String> contents = new ArrayList<>();
        String actualBpfRcFilePath = BPF_LOADER_RC_FILE_PATH;
        File bpfRcFile = new File(actualBpfRcFilePath);
        if (!bpfRcFile.exists()) {
            actualBpfRcFilePath = NET_BPF_LOAD_RC_FILE_PATH;
            bpfRcFile = new File(actualBpfRcFilePath);
        }
        if (!bpfRcFile.exists()) {
            Log.wtf(TAG, "either " + BPF_LOADER_RC_FILE_PATH + " or " + NET_BPF_LOAD_RC_FILE_PATH
                    + " doesn't exist.");
            return contents;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(actualBpfRcFilePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                line = line.strip();
                if (line.startsWith("#")) {
                    continue;
                }
                contents.add(line);
            }
        } catch (FileNotFoundException e) {
            Log.wtf(BPF_LOADER_RC_FILE_PATH + " doesn't exist.", e);
            contents.clear();
            return contents;
        } catch (IOException e) {
            Log.wtf("read " + BPF_LOADER_RC_FILE_PATH + " file failed.", e);
            contents.clear();
            return contents;
        }
        return contents;
    }

    /**
     * Check the bpfLoader rc file on the system image matches any of the template files.
     */
    public static void checkBpfLoaderRc() {
        if (!SdkLevel.isAtLeastS()) {
            return;
        }
        final List<String> bpfRcFileLines = loadExistingBpfRcFile();
        boolean isSame = false;
        for (List<String> bpfRcTemplate : BPF_RC_FILE_TEMPLATES) {
            if (bpfRcTemplate.equals(bpfRcFileLines)) {
                isSame = true;
                break;
            }
        }
        if (!isSame) {
            Log.wtf(TAG, "BPF rc file is not same as any of the template files " + bpfRcFileLines);
        }
    }
}
