/**
 * Copyright (c) 2023, The Android Open Source Project
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

#pragma once

#include <condition_variable>
#include <string>
#include <thread>
#include <unordered_map>

#include "android-base/thread_annotations.h"
#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {
namespace internal {

// NetworkTracePoller is responsible for interactions with the BPF ring buffer
// including polling. This class is an internal helper for NetworkTraceHandler,
// it is not meant to be used elsewhere.
class NetworkTracePoller {
 public:
  using EventSink = std::function<void(const std::vector<PacketTrace>&)>;

  // Initialize with a callback capable of intercepting data.
  NetworkTracePoller(EventSink callback) : mCallback(std::move(callback)) {}

  ~NetworkTracePoller();

  // Starts tracing with the given poll interval.
  bool Start(uint32_t pollMs) EXCLUDES(mMutex);

  // Stops tracing and release any held state.
  bool Stop() EXCLUDES(mMutex);

  // Consumes all available events from the ringbuffer.
  bool ConsumeAll() EXCLUDES(mMutex) EXCLUDES(mMutex) EXCLUDES(mCollectionMutex);

 private:
  void ThreadMain(uint32_t poll_ms) EXCLUDES(mCollectionMutex);
  bool ConsumeAllLocked() REQUIRES(mCollectionMutex);
  bool DoStart(uint32_t pollMs) REQUIRES(mMutex) EXCLUDES(mCollectionMutex);
  bool DoStop() REQUIRES(mMutex) EXCLUDES(mCollectionMutex);

  std::mutex mMutex;

  // Records the number of successfully started active sessions so that only the
  // first active session attempts setup and only the last cleans up. Note that
  // the session count will remain zero if Start fails. It is expected that Stop
  // will not be called for any trace session where Start fails.
  int mSessionCount GUARDED_BY(mMutex);

  // How often to poll the ring buffer, defined by the trace config.
  uint32_t mPollMs GUARDED_BY(mMutex);

  std::mutex mCollectionMutex ACQUIRED_AFTER(mMutex);

  // The function to process PacketTrace, typically a Perfetto sink.
  EventSink mCallback GUARDED_BY(mCollectionMutex);

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<PacketTrace>> mRingBuffer GUARDED_BY(mCollectionMutex);

  // The packet tracing config map (really a 1-element array).
  BpfMap<uint32_t, bool> mConfigurationMap GUARDED_BY(mCollectionMutex);

  std::condition_variable mStopCond GUARDED_BY(mCollectionMutex);
  bool mStop GUARDED_BY(mCollectionMutex) = true;

  std::optional<std::thread> mThread GUARDED_BY(mMutex);
};

}  // namespace internal
}  // namespace bpf
}  // namespace android
