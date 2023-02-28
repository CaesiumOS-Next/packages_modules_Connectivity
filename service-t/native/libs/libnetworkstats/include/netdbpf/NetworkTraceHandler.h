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
#include <perfetto/tracing.h>

#include "netdbpf/NetworkTracePoller.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {

class NetworkTraceHandler : public perfetto::DataSource<NetworkTraceHandler> {
 public:
  // Registers this DataSource.
  static void RegisterDataSource();

  // Connects to the system Perfetto daemon and registers the trace handler.
  static void InitPerfettoTracing();

  // perfetto::DataSource overrides:
  void OnSetup(const SetupArgs& args) override;
  void OnStart(const StartArgs&) override;
  void OnStop(const StopArgs&) override;

 private:
  // Returns a singleton instance of the poller.
  static NetworkTracePoller& Poller();

  // Convert a PacketTrace into a Perfetto trace packet.
  static void Fill(const PacketTrace& src,
                   ::perfetto::protos::pbzero::TracePacket& dst);

  uint32_t mPollMs;
  bool mStarted;
};

}  // namespace bpf
}  // namespace android
