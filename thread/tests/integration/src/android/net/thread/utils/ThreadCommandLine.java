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

import static org.junit.Assert.fail;
import android.app.UiAutomation;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** A wrapper of devices or binaries which can execute the OpenThread CLI commands. */
abstract class ThreadCommandLine {
    private final String mBinaryWithArgs;
    private Process mProcess;
    private BufferedReader mReader;
    private BufferedReader mErrorReader;
    private OutputStreamWriter mWriter;

    /**
     * Creates a new {@link ThreadCommandLine} object by executing the specified string command in a
     * separate process.
     *
     * <p>An example of {@code binaryWithArgs} is "/system/bin/ot-cli-ftd 5" which starts a
     * "ot-cli-ftd" program with node ID 5.
     *
     * @throws IllegalStateException if failed to execute the command
     */
    public ThreadCommandLine(String binaryWithArgs) {
        mBinaryWithArgs = binaryWithArgs;
        restart();
    }

    /** Restarts the command. */
    public void restart() {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            ParcelFileDescriptor[] fds = automation.executeShellCommandRwe(mBinaryWithArgs);
            ParcelFileDescriptor fdOut = fds[0];
            ParcelFileDescriptor fdIn = fds[1];
            ParcelFileDescriptor fdErr = fds[2];

            try (FileOutputStream fos = new ParcelFileDescriptor.AutoCloseOutputStream(fdIn)) {
                mWriter = new OutputStreamWriter(fos);
            }
            try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(fdOut)) {
                mReader = new BufferedReader(new InputStreamReader(fis));
            }
            try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(fdErr)) {
                mErrorReader = new BufferedReader(new InputStreamReader(fis));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run command \"" + mBinaryWithArgs + "\"", e);
        }
    }

    public void exit() {
        executeCommand("exit");
    }

    /** Executes the "factoryreset" command. */
    public abstract void factoryReset();

    /**
     * Writes a CLI command to input of the underlying process. Always prefer {@link
     * #executeCommand} unless specific CLI control is required.
     *
     * @throws IllegalStateException if failed to write the command
     */
    protected void writeCommand(String command) {
        try {
            mWriter.write(command + "\n");
            mWriter.flush();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write the command \""
                            + command
                            + "\" to \""
                            + mBinaryWithArgs
                            + "\"",
                    e);
        }
    }

    /**
     * Executes a CLI command and waits for the result.
     *
     * @throws IllegalStateException if failed to execute the command or get the result
     */
    public List<String> executeCommand(String command) {
        writeCommand(command);
        try {
            return readUntilDone();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read output of command \""
                            + command
                            + "\" from \""
                            + mBinaryWithArgs
                            + "\"",
                    e);
        }
    }

    private List<String> readUntilDone() throws IOException {
        ArrayList<String> result = new ArrayList<>();
        String line;
        while ((line = mReader.readLine()) != null) {
            if (line.equals("Done")) {
                break;
            }
            if (line.startsWith("Error:")) {
                fail("ot-cli-ftd reported an error: " + line);
            }
            if (!line.startsWith("> ")) {
                result.add(line);
            }
        }
        if (!"Done".equals(line)) {
            throw new IllegalStateException(
                    "Didn't get \"Done\" in output, error: " + mErrorReader.readLine());
        }
        return result;
    }
}
