/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.thread.utils;

import java.util.List;

/**
 * Wrapper of the "/system/bin/ot-ctl" which can be used to send CLI commands to ot-daemon to
 * control its behavior.
 *
 * <p>Note that this class takes root privileged to run.
 */
public final class OtDaemonController extends ThreadCommandLine {
    private static final String OT_CTL = "/system/bin/ot-ctl";

    public OtDaemonController() {
        super(OT_CTL);
    }

    @Override
    public void factoryReset() {
        try {
            List<String> result = executeCommand("ifconfig");
            if (result.isEmpty() || !result.get(0).equals("up")) {
                throw new IllegalStateException("ifconfig output: " + result.get(0));
            }
            // writeCommand("factoryreset");
            // } catch (IllegalStateException e) {
            // It's expected to throw because ot-daemon will exit
        } finally {
            // "factoryreset" causes ot-daemon and ot-ctl to exit, here restarts another ot-ctl
            // process

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

            restart();
        }
    }
}
