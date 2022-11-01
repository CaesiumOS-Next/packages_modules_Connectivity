/*
 * Copyright (C) 2022 The Android Open Source Project
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

// Anything past the bpfloader version shipped in T  (ie. effectively U+)
#define BPFLOADER_MIN_VER (BPFLOADER_T_VERSION + 1)

#include "bpf_helpers.h"

// This macro defines an invalid (infite loop) bpf program
// that must successfully load on any kernels in [min_kver, max_kver).
//
// Thus effectively making booting such a kernel impossible post-T.
// This is an attempt at making some sort of minimum guarantees for mainline.

#define DISALLOW_KVER(NAME, min_kver, max_kver) \
DEFINE_BPF_PROG_KVER_RANGE("schedcls/kernel$" #NAME, AID_ROOT, AID_ROOT, \
                           sched_cls_kernel_##NAME, (min_kver), (max_kver)) \
(struct __sk_buff* skb) { \
    for (;;); \
}

// 4.14/4.19/5.4 minimum LTS versions are chosen based on when
// ACK picked up Apache2 support for bpf_ktime_get{,_boot}_ns()
// However this should probably be further restricted,
// as there are likely to be other requirements...
DISALLOW_KVER(4_14, KVER_NONE,      KVER(4, 14, 222));  // Allows LTS: 4.14.222+
DISALLOW_KVER(4_19, KVER(4, 15, 0), KVER(4, 19, 176));  // Allows LTS: 4.19.176+
DISALLOW_KVER(5_4,  KVER(4, 20, 0), KVER(5,  4,  98));  // Allows LTS: 5.4.98+
DISALLOW_KVER(5_10, KVER(5,  5, 0), KVER(5, 10,   0));  // Allows LTS: 5.10.0+
DISALLOW_KVER(5_15, KVER(5, 11, 0), KVER(5, 15,   0));  // Allows LTS: 5.15.0+
DISALLOW_KVER(6_1,  KVER(5, 16, 0), KVER(6,  1,   0));  // Allows dev: 6.1+

LICENSE("Apache 2.0");
CRITICAL("Connectivity");
