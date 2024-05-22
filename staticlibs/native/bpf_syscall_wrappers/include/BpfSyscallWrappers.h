/*
 * Copyright (C) 2021 The Android Open Source Project
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

#pragma once

#include <errno.h>
#include <linux/bpf.h>
#include <linux/unistd.h>
#include <stdint.h>
#include <type_traits>
#include <unistd.h>

namespace android {
namespace base {

// Forward declarations used for BPF_FD_TO_U32 template specialization.
template<typename T>
class unique_fd_impl;
struct DefaultCloser;
using unique_fd = unique_fd_impl<DefaultCloser>;

} // namespace base
namespace bpf {

template<typename T, typename Enable = void>
inline __u32 BPF_FD_TO_U32(const T& fd) {
    return static_cast<__u32>(fd);
}

// In order to support BPF_FD_TO_U32 in places where libbase is not available, a template
// specialization for unique_fd cannot be used directly. Instead, use std::enable_if_t
// metaprogramming to conditionally enable this template.
template<typename T, std::enable_if_t<std::is_same<T, base::unique_fd>::value>>
inline __u32 BPF_FD_TO_U32(const T& fd) {
    return static_cast<__u32>(fd.get());
}


inline uint64_t ptr_to_u64(const void * const x) {
    return (uint64_t)(uintptr_t)x;
}

/* Note: bpf_attr is a union which might have a much larger size then the anonymous struct portion
 * of it that we are using.  The kernel's bpf() system call will perform a strict check to ensure
 * all unused portions are zero.  It will fail with E2BIG if we don't fully zero bpf_attr.
 */

inline int bpf(enum bpf_cmd cmd, const bpf_attr& attr) {
    return syscall(__NR_bpf, cmd, &attr, sizeof(attr));
}

// this version is meant for use with cmd's which mutate the argument
inline int bpf(enum bpf_cmd cmd, bpf_attr *attr) {
    return syscall(__NR_bpf, cmd, attr, sizeof(*attr));
}

inline int createMap(bpf_map_type map_type, uint32_t key_size, uint32_t value_size,
                     uint32_t max_entries, uint32_t map_flags) {
    return bpf(BPF_MAP_CREATE, {
                                       .map_type = map_type,
                                       .key_size = key_size,
                                       .value_size = value_size,
                                       .max_entries = max_entries,
                                       .map_flags = map_flags,
                               });
}

// Note:
//   'map_type' must be one of BPF_MAP_TYPE_{ARRAY,HASH}_OF_MAPS
//   'value_size' must be sizeof(u32), ie. 4
//   'inner_map_fd' is basically a template specifying {map_type, key_size, value_size, max_entries, map_flags}
//   of the inner map type (and possibly only key_size/value_size actually matter?).
template<typename BPF_FD_TYPE>
inline int createOuterMap(bpf_map_type map_type, uint32_t key_size, uint32_t value_size,
                          uint32_t max_entries, uint32_t map_flags, const BPF_FD_TYPE& inner_map_fd) {
    return bpf(BPF_MAP_CREATE, {
                                       .map_type = map_type,
                                       .key_size = key_size,
                                       .value_size = value_size,
                                       .max_entries = max_entries,
                                       .map_flags = map_flags,
                                       .inner_map_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(inner_map_fd),
                               });
}

template<typename BPF_FD_TYPE>
inline int writeToMapEntry(const BPF_FD_TYPE& map_fd, const void* key, const void* value,
                           uint64_t flags) {
    return bpf(BPF_MAP_UPDATE_ELEM, {
                                            .map_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(map_fd),
                                            .key = ptr_to_u64(key),
                                            .value = ptr_to_u64(value),
                                            .flags = flags,
                                    });
}

template<typename BPF_FD_TYPE>
inline int findMapEntry(const BPF_FD_TYPE& map_fd, const void* key, void* value) {
    return bpf(BPF_MAP_LOOKUP_ELEM, {
                                            .map_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(map_fd),
                                            .key = ptr_to_u64(key),
                                            .value = ptr_to_u64(value),
                                    });
}

template<typename BPF_FD_TYPE>
inline int deleteMapEntry(const BPF_FD_TYPE& map_fd, const void* key) {
    return bpf(BPF_MAP_DELETE_ELEM, {
                                            .map_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(map_fd),
                                            .key = ptr_to_u64(key),
                                    });
}

template<typename BPF_FD_TYPE>
inline int getNextMapKey(const BPF_FD_TYPE& map_fd, const void* key, void* next_key) {
    return bpf(BPF_MAP_GET_NEXT_KEY, {
                                             .map_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(map_fd),
                                             .key = ptr_to_u64(key),
                                             .next_key = ptr_to_u64(next_key),
                                     });
}

template<typename BPF_FD_TYPE>
inline int getFirstMapKey(const BPF_FD_TYPE& map_fd, void* firstKey) {
    return getNextMapKey(map_fd, NULL, firstKey);
}

template<typename BPF_FD_TYPE>
inline int bpfFdPin(const BPF_FD_TYPE& map_fd, const char* pathname) {
    return bpf(BPF_OBJ_PIN, {
                                    .pathname = ptr_to_u64(pathname),
                                    .bpf_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(map_fd),
                            });
}

inline int bpfFdGet(const char* pathname, uint32_t flag) {
    return bpf(BPF_OBJ_GET, {
                                    .pathname = ptr_to_u64(pathname),
                                    .file_flags = flag,
                            });
}

inline int mapRetrieve(const char* pathname, uint32_t flag) {
    return bpfFdGet(pathname, flag);
}

inline int mapRetrieveRW(const char* pathname) {
    return mapRetrieve(pathname, 0);
}

inline int mapRetrieveRO(const char* pathname) {
    return mapRetrieve(pathname, BPF_F_RDONLY);
}

inline int mapRetrieveWO(const char* pathname) {
    return mapRetrieve(pathname, BPF_F_WRONLY);
}

inline int retrieveProgram(const char* pathname) {
    return bpfFdGet(pathname, BPF_F_RDONLY);
}

inline bool usableProgram(const char* pathname) {
    int fd = retrieveProgram(pathname);
    bool ok = (fd >= 0);
    if (ok) close(fd);
    return ok;
}

template<typename BPF_FD_TYPE>
inline int attachProgram(bpf_attach_type type, const BPF_FD_TYPE& prog_fd,
                         const BPF_FD_TYPE& cg_fd, uint32_t flags = 0) {
    return bpf(BPF_PROG_ATTACH, {
                                        .target_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(cg_fd),
                                        .attach_bpf_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(prog_fd),
                                        .attach_type = type,
                                        .attach_flags = flags,
                                });
}

template<typename BPF_FD_TYPE>
inline int detachProgram(bpf_attach_type type, const BPF_FD_TYPE& cg_fd) {
    return bpf(BPF_PROG_DETACH, {
                                        .target_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(cg_fd),
                                        .attach_type = type,
                                });
}

template<typename BPF_FD_TYPE>
inline int queryProgram(const BPF_FD_TYPE& cg_fd,
                        enum bpf_attach_type attach_type,
                        __u32 query_flags = 0,
                        __u32 attach_flags = 0) {
    int prog_id = -1;  // equivalent to an array of one integer.
    bpf_attr arg = {
            .query = {
                    .target_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(cg_fd),
                    .attach_type = attach_type,
                    .query_flags = query_flags,
                    .attach_flags = attach_flags,
                    .prog_ids = ptr_to_u64(&prog_id),  // pointer to output array
                    .prog_cnt = 1,  // in: space - nr of ints in the array, out: used
            }
    };
    int v = bpf(BPF_PROG_QUERY, &arg);
    if (v) return v;  // error case
    if (!arg.query.prog_cnt) return 0;  // no program, kernel never returns zero id
    return prog_id;  // return actual id
}

template<typename BPF_FD_TYPE>
inline int detachSingleProgram(bpf_attach_type type, const BPF_FD_TYPE& prog_fd,
                               const BPF_FD_TYPE& cg_fd) {
    return bpf(BPF_PROG_DETACH, {
                                        .target_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(cg_fd),
                                        .attach_bpf_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(prog_fd),
                                        .attach_type = type,
                                });
}

// Available in 4.12 and later kernels.
template<typename BPF_FD_TYPE>
inline int runProgram(const BPF_FD_TYPE& prog_fd, const void* data,
                      const uint32_t data_size) {
    return bpf(BPF_PROG_RUN, {
                                     .test = {
                                             .prog_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(prog_fd),
                                             .data_size_in = data_size,
                                             .data_in = ptr_to_u64(data),
                                     },
                             });
}

// BPF_OBJ_GET_INFO_BY_FD requires 4.14+ kernel
//
// Note: some fields are only defined in newer kernels (ie. the map_info struct grows
// over time), so we need to check that the field we're interested in is actually
// supported/returned by the running kernel.  We do this by checking it is fully
// within the bounds of the struct size as reported by the kernel.
#define DEFINE_BPF_GET_FD(TYPE, NAME, FIELD) \
template<typename BPF_FD_TYPE> \
inline int bpfGetFd ## NAME(const BPF_FD_TYPE& fd) { \
    struct bpf_ ## TYPE ## _info info = {}; \
    union bpf_attr attr = { .info = { \
        .bpf_fd = BPF_FD_TO_U32<BPF_FD_TYPE>(fd), \
        .info_len = sizeof(info), \
        .info = ptr_to_u64(&info), \
    }}; \
    int rv = bpf(BPF_OBJ_GET_INFO_BY_FD, attr); \
    if (rv) return rv; \
    if (attr.info.info_len < offsetof(bpf_ ## TYPE ## _info, FIELD) + sizeof(info.FIELD)) { \
        errno = EOPNOTSUPP; \
        return -1; \
    }; \
    return info.FIELD; \
}

// All 7 of these fields are already present in Linux v4.14 (even ACK 4.14-P)
// while BPF_OBJ_GET_INFO_BY_FD is not implemented at all in v4.9 (even ACK 4.9-Q)
DEFINE_BPF_GET_FD(map, MapType, type)            // int bpfGetFdMapType(const BPF_FD_TYPE map_fd)
DEFINE_BPF_GET_FD(map, MapId, id)                // int bpfGetFdMapId(const BPF_FD_TYPE map_fd)
DEFINE_BPF_GET_FD(map, KeySize, key_size)        // int bpfGetFdKeySize(const BPF_FD_TYPE map_fd)
DEFINE_BPF_GET_FD(map, ValueSize, value_size)    // int bpfGetFdValueSize(const BPF_FD_TYPE map_fd)
DEFINE_BPF_GET_FD(map, MaxEntries, max_entries)  // int bpfGetFdMaxEntries(const BPF_FD_TYPE map_fd)
DEFINE_BPF_GET_FD(map, MapFlags, map_flags)      // int bpfGetFdMapFlags(const BPF_FD_TYPE map_fd)
DEFINE_BPF_GET_FD(prog, ProgId, id)              // int bpfGetFdProgId(const BPF_FD_TYPE prog_fd)

#undef DEFINE_BPF_GET_FD

}  // namespace bpf
}  // namespace android

