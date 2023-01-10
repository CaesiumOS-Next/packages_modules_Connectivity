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

#define LOG_TAG "NetworkTrace"

#include "netdbpf/NetworkTraceHandler.h"

#include <bpf/BpfUtils.h>
#include <log/log.h>

namespace android {
namespace bpf {

bool NetworkTraceHandler::Start() {
  SLOGD("Starting datasource");

  auto status = mConfigurationMap.init(PACKET_INFO_CONFIG_MAP_PATH);
  if (!status.ok()) {
    SLOGW("Failed to bind config map: %s", status.error().message().c_str());
    return false;
  }

  auto rb = BpfRingbuf<PacketInfo>::Create(PACKET_INFO_MAP_PATH, mCallback);
  if (!rb.ok()) {
    SLOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  mRingBuffer = std::move(*rb);

  auto res = mConfigurationMap.writeValue(0, 1, BPF_ANY);
  if (!res.ok()) {
    SLOGW("Failed to enable tracing: %s", res.error().message().c_str());
    return false;
  }

  return true;
}

bool NetworkTraceHandler::Stop() {
  SLOGD("Stopping datasource");

  auto res = mConfigurationMap.writeValue(0, 0, BPF_ANY);
  if (!res.ok()) {
    SLOGW("Failed to disable tracing: %s", res.error().message().c_str());
    return false;
  }

  return true;
}

bool NetworkTraceHandler::PollOnce() {
  base::Result<int> ret = mRingBuffer->consume();
  if (!ret.ok()) {
    SLOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
    return false;
  }
  return true;
}

}  // namespace bpf
}  // namespace android
