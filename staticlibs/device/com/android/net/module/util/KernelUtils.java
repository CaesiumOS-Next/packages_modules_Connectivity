/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.net.module.util.JniUtil;

/**
 * BpfMap is a key -> value mapping structure that is designed to maintained the bpf map entries.
 * This is a wrapper class of in-kernel data structure. The in-kernel data can be read/written by
 * passing syscalls with map file descriptor.
 *
 * @param <K> the key of the map.
 * @param <V> the value of the map.
 */
@RequiresApi(Build.VERSION_CODES.S)
public class KernelUtils {
    static {
        System.loadLibrary(JniUtil.getJniLibraryName(KernelUtils.class.getPackage()));
    }

    /** Returns whether the Linux Kernel is 64 bit */
    public static native boolean isKernel64Bit();

    /** Returns whether the Linux Kernel is x86 */
    public static native boolean isKernelX86();
}
