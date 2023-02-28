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

#include "netdbpf/NetworkTraceLooper.h"

#include <bpf/BpfUtils.h>
#include <log/log.h>
#include <perfetto/tracing/platform.h>
#include <perfetto/tracing/tracing.h>

namespace android {
namespace bpf {

void NetworkTraceLooper::Loop() {
  mTaskRunner->PostDelayedTask([this]() { Loop(); }, mPollMs);
  ConsumeAll();
}

bool NetworkTraceLooper::Start(uint32_t pollMs) {
  ALOGD("Starting datasource");

  mPollMs = pollMs;
  if (mPollMs < 100) {
    ALOGI("poll_ms is missing or below the 100ms minimum. Increasing to 100ms");
    mPollMs = 100;
  }

  auto status = mConfigurationMap.init(PACKET_TRACE_ENABLED_MAP_PATH);
  if (!status.ok()) {
    ALOGW("Failed to bind config map: %s", status.error().message().c_str());
    return false;
  }

  auto rb = BpfRingbuf<PacketTrace>::Create(PACKET_TRACE_RINGBUF_PATH);
  if (!rb.ok()) {
    ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  mRingBuffer = std::move(*rb);

  auto res = mConfigurationMap.writeValue(0, true, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to enable tracing: %s", res.error().message().c_str());
    return false;
  }

  // Start a task runner to run ConsumeAll every mPollMs milliseconds.
  mTaskRunner = perfetto::Platform::GetDefaultPlatform()->CreateTaskRunner({});
  Loop();

  return true;
}

bool NetworkTraceLooper::Stop() {
  ALOGD("Stopping datasource");

  auto res = mConfigurationMap.writeValue(0, false, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to disable tracing: %s", res.error().message().c_str());
  }

  mTaskRunner.reset();
  mRingBuffer.reset();

  return res.ok();
}

bool NetworkTraceLooper::ConsumeAll() {
  if (mRingBuffer == nullptr) {
    ALOGW("Tracing is not active");
    return false;
  }

  base::Result<int> ret = mRingBuffer->ConsumeAll(mCallback);
  if (!ret.ok()) {
    ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
    return false;
  }

  return true;
}

}  // namespace bpf
}  // namespace android
