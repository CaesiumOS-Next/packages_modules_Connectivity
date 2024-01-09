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
package com.android.net.module.util;

import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.IOException;

/**
 * The classes and the methods for Kernel info
 *
 * <p>{@hide}
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class KernelUtils {
    static {
        System.loadLibrary(JniUtil.getJniLibraryName(KernelUtils.class.getPackage()));
    }

    public static boolean isKernel64Bit() throws IOException {
        return native_isKernel64Bit();
    }

    private static native boolean native_isKernel64Bit() throws IOException;
}
