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

#include <perfetto/base/task_runner.h>
#include <perfetto/tracing/data_source.h>

#include <string>
#include <unordered_map>

#include "android-base/thread_annotations.h"
#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {

class NetworkTraceHandler {
 public:
  // Registers this DataSource.
  static void RegisterDataSource();

  // Connects to the system Perfetto daemon and registers the trace handler.
  static void InitPerfettoTracing();

  // Returns a singleton instance of the handler.
  static NetworkTraceHandler& Instance() {
    static NetworkTraceHandler sHandler;
    return sHandler;
  }

  // Testonly: initialize with a callback capable of intercepting data. This
  // sets mPollMs high enough that the periodic thread will never run in tests.
  NetworkTraceHandler(std::function<void(const PacketTrace&)> callback)
      : mPollMs(10000), mCallback(std::move(callback)) {}

  // Testonly: standalone functions without perfetto dependency.
  bool Start() EXCLUDES(mMutex);
  bool Stop() EXCLUDES(mMutex);
  bool ConsumeAll() EXCLUDES(mMutex);

  // perfetto::DataSource delegate.
  void OnSetup(const perfetto::DataSourceBase::SetupArgs& args)
      EXCLUDES(mMutex);

  // Convert a PacketTrace into a Perfetto trace packet.
  void Fill(const PacketTrace& src,
            ::perfetto::protos::pbzero::TracePacket& dst);

 private:
  // Initialize with the default Perfetto callback.
  NetworkTraceHandler();

  void Loop() REQUIRES(mMutex);

  // Record the active session count so that only the first concurrent session
  // starts tracing, and only the last conccurrent session cleans up.
  std::mutex mMutex;
  int mSessionCount GUARDED_BY(mMutex) = 0;

  // How often to poll the ring buffer, defined by the trace config.
  uint32_t mPollMs GUARDED_BY(mMutex);

  // The function to process PacketTrace, typically a Perfetto sink.
  std::function<void(const PacketTrace&)> mCallback GUARDED_BY(mMutex);

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<PacketTrace>> mRingBuffer GUARDED_BY(mMutex);

  // The packet tracing config map (really a 1-element array).
  BpfMap<uint32_t, bool> mConfigurationMap GUARDED_BY(mMutex);

  // This must be the last member, causing it to be the first deleted. If it is
  // not, members required for callbacks can be deleted before it's stopped.
  std::unique_ptr<perfetto::base::TaskRunner> mTaskRunner GUARDED_BY(mMutex);
};

}  // namespace bpf
}  // namespace android
