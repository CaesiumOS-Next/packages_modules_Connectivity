/**
 * Copyright (c) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "BpfHandler"

#include "BpfHandler.h"

#include <linux/bpf.h>

#include <android-base/unique_fd.h>
#include <log/log.h>
#include <processgroup/processgroup.h>

#include "BpfSyscallWrappers.h"
#include "bpf_shared.h"

namespace android {
namespace net {

using base::unique_fd;
using bpf::retrieveProgram;
using netdutils::Status;
using netdutils::statusFromErrno;

static Status attachProgramToCgroup(const char* programPath, const unique_fd& cgroupFd,
                                    bpf_attach_type type) {
    unique_fd cgroupProg(retrieveProgram(programPath));
    if (cgroupProg == -1) {
        int ret = errno;
        ALOGE("Failed to get program from %s: %s", programPath, strerror(ret));
        return statusFromErrno(ret, "cgroup program get failed");
    }
    if (android::bpf::attachProgram(type, cgroupProg, cgroupFd)) {
        int ret = errno;
        ALOGE("Program from %s attach failed: %s", programPath, strerror(ret));
        return statusFromErrno(ret, "program attach failed");
    }
    return netdutils::status::ok;
}

static Status initPrograms() {
    std::string cg2_path;

    if (!CgroupGetControllerPath(CGROUPV2_CONTROLLER_NAME, &cg2_path)) {
         int ret = errno;
         ALOGE("Failed to find cgroup v2 root");
         return statusFromErrno(ret, "Failed to find cgroup v2 root");
    }

    unique_fd cg_fd(open(cg2_path.c_str(), O_DIRECTORY | O_RDONLY | O_CLOEXEC));
    if (cg_fd == -1) {
        int ret = errno;
        ALOGE("Failed to open the cgroup directory: %s", strerror(ret));
        return statusFromErrno(ret, "Open the cgroup directory failed");
    }
    RETURN_IF_NOT_OK(attachProgramToCgroup(BPF_EGRESS_PROG_PATH, cg_fd, BPF_CGROUP_INET_EGRESS));
    RETURN_IF_NOT_OK(attachProgramToCgroup(BPF_INGRESS_PROG_PATH, cg_fd, BPF_CGROUP_INET_INGRESS));

    // For the devices that support cgroup socket filter, the socket filter
    // should be loaded successfully by bpfloader. So we attach the filter to
    // cgroup if the program is pinned properly.
    // TODO: delete the if statement once all devices should support cgroup
    // socket filter (ie. the minimum kernel version required is 4.14).
    if (!access(CGROUP_SOCKET_PROG_PATH, F_OK)) {
        RETURN_IF_NOT_OK(
                attachProgramToCgroup(CGROUP_SOCKET_PROG_PATH, cg_fd, BPF_CGROUP_INET_SOCK_CREATE));
    }
    return netdutils::status::ok;
}

Status BpfHandler::init() {
    RETURN_IF_NOT_OK(initPrograms());

    return netdutils::status::ok;
}

}  // namespace net
}  // namespace android