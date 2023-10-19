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

#include <android-base/macros.h>
#include <arpa/inet.h>
#include <bpf/BpfUtils.h>
#include <log/log.h>
#include <perfetto/public/data_source.h>
#include <perfetto/public/pb_decoder.h>
#include <perfetto/public/producer.h>
#include <perfetto/public/protos/config/data_source_config.pzc.h>
#include <perfetto/public/protos/trace/interned_data/interned_data.pzc.h>

#include "perfetto/public/protos/config/android/network_trace_config.pzc.h"
#include "perfetto/public/protos/trace/android/network_trace.pzc.h"

namespace android {
namespace bpf {
namespace {

using ::android::bpf::internal::NetworkTracePoller;

struct PerfettoDs g_data_source = PERFETTO_DS_INIT();

// Bundling takes groups of packets with similar contextual fields (generally,
// all fields except timestamp and length) and summarises them in a single trace
// packet. For example, rather than
//
//   {.timestampNs = 1, .uid = 1000, .tag = 123, .len = 72}
//   {.timestampNs = 2, .uid = 1000, .tag = 123, .len = 100}
//   {.timestampNs = 5, .uid = 1000, .tag = 123, .len = 456}
//
// The output will be something like
//   {
//     .timestamp = 1
//     .ctx = {.uid = 1000, .tag = 123}
//     .timestamp = [0, 1, 4], // delta encoded
//     .length = [72, 100, 456], // should be zipped with timestamps
//   }
//
// Most workloads have many packets from few contexts. Bundling greatly reduces
// the amount of redundant information written, thus reducing the overall trace
// size. Interning ids are similarly based on unique bundle contexts.

// Based on boost::hash_combine
template <typename T, typename... Rest>
void HashCombine(std::size_t& seed, const T& val, const Rest&... rest) {
  seed ^= std::hash<T>()(val) + 0x9e3779b9 + (seed << 6) + (seed >> 2);
  (HashCombine(seed, rest), ...);
}

}  // namespace

// Details summarises the timestamp and lengths of packets in a bundle.
struct NetworkTraceHandler::BundleDetails {
  std::vector<std::pair<uint64_t, uint32_t>> time_and_len;
  uint64_t minTs = std::numeric_limits<uint64_t>::max();
  uint64_t maxTs = std::numeric_limits<uint64_t>::min();
  uint32_t bytes = 0;
};

BundleKey::BundleKey(const PacketTrace& pkt)
    : ifindex(pkt.ifindex),
      uid(pkt.uid),
      tag(pkt.tag),
      egress(pkt.egress),
      ipProto(pkt.ipProto),
      ipVersion(pkt.ipVersion) {
  switch (ipProto) {
    case IPPROTO_TCP:
      tcpFlags = pkt.tcpFlags;
      FALLTHROUGH_INTENDED;
    case IPPROTO_DCCP:
    case IPPROTO_UDP:
    case IPPROTO_UDPLITE:
    case IPPROTO_SCTP:
      localPort = ntohs(pkt.egress ? pkt.sport : pkt.dport);
      remotePort = ntohs(pkt.egress ? pkt.dport : pkt.sport);
      break;
    case IPPROTO_ICMP:
    case IPPROTO_ICMPV6:
      icmpType = ntohs(pkt.sport);
      icmpCode = ntohs(pkt.dport);
      break;
  }
}

#define AGG_FIELDS(x)                                                    \
  (x).ifindex, (x).uid, (x).tag, (x).egress, (x).ipProto, (x).ipVersion, \
      (x).tcpFlags, (x).localPort, (x).remotePort, (x).icmpType, (x).icmpCode

std::size_t BundleHash::operator()(const BundleKey& a) const {
  std::size_t seed = 0;
  HashCombine(seed, AGG_FIELDS(a));
  return seed;
}

bool BundleEq::operator()(const BundleKey& a, const BundleKey& b) const {
  return std::tie(AGG_FIELDS(a)) == std::tie(AGG_FIELDS(b));
}

// static
void NetworkTraceHandler::RegisterDataSource(bool isTest) {
  ALOGD("Registering Perfetto data source");
  static bool registered = false;
  if (!registered) {
    struct PerfettoDsParams params = PerfettoDsParamsDefault();
    params.user_arg = reinterpret_cast<void*>(isTest);
    params.on_setup_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex,
                            void* ds_config, size_t ds_config_size,
                            void* user_arg,
                            struct PerfettoDsOnSetupArgs*) -> void* {
      auto* handler = new NetworkTraceHandler(static_cast<bool>(user_arg));
      handler->OnSetup(ds_config, ds_config_size);
      return static_cast<void*>(handler);
    };
    params.on_start_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex,
                            void*, void* inst_ctx,
                            struct PerfettoDsOnStartArgs*) {
      auto* handler = static_cast<NetworkTraceHandler*>(inst_ctx);
      handler->OnStart();
    };
    params.on_stop_cb = [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex,
                           void*, void* inst_ctx,
                           struct PerfettoDsOnStopArgs*) {
      auto* handler = static_cast<NetworkTraceHandler*>(inst_ctx);
      handler->OnStop();
    };
    params.on_destroy_cb = [](struct PerfettoDsImpl*, void*, void* inst_ctx) {
      auto* handler = static_cast<NetworkTraceHandler*>(inst_ctx);
      delete handler;
    };
    params.on_create_incr_cb =
        [](struct PerfettoDsImpl*, PerfettoDsInstanceIndex,
           struct PerfettoDsTracerImpl*,
           void*) -> void* { return new NetworkTraceState(); };
    params.on_delete_incr_cb = [](void* obj) {
      delete static_cast<NetworkTraceState*>(obj);
    };

    const char* name;
    if (!isTest) {
      name = "android.network_packets";
    } else {
      name = "test.network_packets";
    }
    PerfettoDsRegister(&g_data_source, name, params);
    registered = true;
  }
}

// static
void NetworkTraceHandler::InitPerfettoTracing() {
  struct PerfettoProducerInitArgs args = PERFETTO_PRODUCER_INIT_ARGS_INIT();
  args.backends = PERFETTO_BACKEND_SYSTEM;
  PerfettoProducerInit(args);

  NetworkTraceHandler::RegisterDataSource();
}

NetworkTracePoller NetworkTraceHandler::sPoller(NetworkTraceHandler::DoTrace);

// static
void NetworkTraceHandler::DoTrace(const std::vector<PacketTrace>& packets) {
  // PERFETTO_DS_TRACE executes the block for each active session. The context
  // gets a reference to the NetworkTraceHandler instance associated with
  // the session and delegates writing. The corresponding handler will write
  // with the setting specified in the trace config.
  PERFETTO_DS_TRACE(g_data_source, ctx) {
    void* handle =
        PerfettoDsImplGetInstanceLocked(g_data_source.impl, ctx.impl.inst_id);
    // The underlying handle can be invalidated between when Trace starts
    // and GetDataSourceLocked is called, but not while the instance is
    // locked. Check validity prior to use.
    if (!handle) {
      continue;
    }
    reinterpret_cast<NetworkTraceHandler*>(handle)->Write(packets, ctx);
    PerfettoDsImplReleaseInstanceLocked(g_data_source.impl, ctx.impl.inst_id);
  }
}

// static
void NetworkTraceHandler::DoFlush() {
  // PERFETTO_DS_TRACE executes the block for each active session.
  PERFETTO_DS_TRACE(g_data_source, ctx) {
    PerfettoDsTracerFlush(&ctx, nullptr, nullptr);
  }
}

void NetworkTraceHandler::OnSetup(void* ds_config, size_t ds_config_size) {
  for (auto it = PerfettoPbDecoderIterateBegin(ds_config, ds_config_size);
       it.field.status == PERFETTO_PB_DECODER_OK;
       PerfettoPbDecoderIterateNext(&it)) {
    switch (it.field.id) {
      case perfetto_protos_DataSourceConfig_network_packet_trace_config_field_number:
        if (it.field.wire_type != PERFETTO_PB_WIRE_TYPE_DELIMITED) {
          continue;
        }
        for (auto it2 =
                 PerfettoPbDecoderIterateNestedBegin(it.field.value.delimited);
             it2.field.status == PERFETTO_PB_DECODER_OK;
             PerfettoPbDecoderIterateNext(&it2)) {
          switch (it2.field.id) {
            case perfetto_protos_NetworkPacketTraceConfig_poll_ms_field_number:
              PerfettoPbDecoderFieldGetUint32(&it2.field, &mPollMs);
              break;
            case perfetto_protos_NetworkPacketTraceConfig_aggregation_threshold_field_number:
              PerfettoPbDecoderFieldGetUint32(&it2.field,
                                              &mAggregationThreshold);
              break;
            case perfetto_protos_NetworkPacketTraceConfig_intern_limit_field_number:
              PerfettoPbDecoderFieldGetUint32(&it2.field, &mInternLimit);
              break;
            case perfetto_protos_NetworkPacketTraceConfig_drop_local_port_field_number:
              PerfettoPbDecoderFieldGetBool(&it2.field, &mDropLocalPort);
              break;
            case perfetto_protos_NetworkPacketTraceConfig_drop_remote_port_field_number:
              PerfettoPbDecoderFieldGetBool(&it2.field, &mDropRemotePort);
              break;
            case perfetto_protos_NetworkPacketTraceConfig_drop_tcp_flags_field_number:
              PerfettoPbDecoderFieldGetBool(&it2.field, &mDropTcpFlags);
              break;
          }
        }
        break;
    }
  }

  if (mPollMs < 100) {
    ALOGI("poll_ms is missing or below the 100ms minimum. Increasing to 100ms");
    mPollMs = 100;
  }
}

void NetworkTraceHandler::OnStart() {
  if (mIsTest) return;  // Don't touch non-hermetic bpf in test.
  mStarted = sPoller.Start(mPollMs);
}

void NetworkTraceHandler::OnStop() {
  if (mIsTest) return;  // Don't touch non-hermetic bpf in test.
  if (mStarted) sPoller.Stop();
  mStarted = false;
}

void NetworkTraceHandler::Write(const std::vector<PacketTrace>& packets,
                                PerfettoDsTracerIterator& ctx) {
  // TODO: remove this fallback once Perfetto stable has support for bundles.
  if (!mInternLimit && !mAggregationThreshold) {
    for (const PacketTrace& pkt : packets) {
      struct PerfettoDsRootTracePacket dst;
      PerfettoDsTracerPacketBegin(&ctx, &dst);
      perfetto_protos_TracePacket_set_timestamp(&dst.msg, pkt.timestampNs);
      struct perfetto_protos_NetworkPacketEvent event;
      perfetto_protos_TracePacket_begin_network_packet(&dst.msg, &event);
      perfetto_protos_NetworkPacketEvent_set_length(&event, pkt.length);
      Fill(BundleKey(pkt), &event);
      perfetto_protos_TracePacket_end_network_packet(&dst.msg, &event);
      PerfettoDsTracerPacketEnd(&ctx, &dst);
    }
    return;
  }

  uint64_t minTs = std::numeric_limits<uint64_t>::max();
  std::unordered_map<BundleKey, BundleDetails, BundleHash, BundleEq> bundles;
  for (const PacketTrace& pkt : packets) {
    BundleKey key(pkt);

    // Dropping fields should remove them from the output and remove them from
    // the aggregation key. Reset the optionals to indicate omission.
    if (mDropTcpFlags) key.tcpFlags.reset();
    if (mDropLocalPort) key.localPort.reset();
    if (mDropRemotePort) key.remotePort.reset();

    minTs = std::min(minTs, pkt.timestampNs);

    BundleDetails& bundle = bundles[key];
    bundle.time_and_len.emplace_back(pkt.timestampNs, pkt.length);
    bundle.minTs = std::min(bundle.minTs, pkt.timestampNs);
    bundle.maxTs = std::max(bundle.maxTs, pkt.timestampNs);
    bundle.bytes += pkt.length;
  }

  auto* incr_state = static_cast<NetworkTraceState*>(
      PerfettoDsGetIncrementalState(&g_data_source, &ctx));

  for (const auto& kv : bundles) {
    const BundleKey& key = kv.first;
    const BundleDetails& details = kv.second;

    struct PerfettoDsRootTracePacket dst;
    PerfettoDsTracerPacketBegin(&ctx, &dst);
    perfetto_protos_TracePacket_set_timestamp(&dst.msg, details.minTs);

    // Incremental state is only used when interning. Set the flag based on
    // whether state was cleared. Leave the flag empty in non-intern configs.
    if (mInternLimit > 0) {
      if (incr_state->cleared) {
        perfetto_protos_TracePacket_set_sequence_flags(
            &dst.msg,
            perfetto_protos_TracePacket_SEQ_INCREMENTAL_STATE_CLEARED);
        incr_state->cleared = false;
      } else {
        perfetto_protos_TracePacket_set_sequence_flags(
            &dst.msg, perfetto_protos_TracePacket_SEQ_NEEDS_INCREMENTAL_STATE);
      }
    }

    FillWithInterning(incr_state, key, details, &dst.msg);

    PerfettoDsTracerPacketEnd(&ctx, &dst);
  }
}

void NetworkTraceHandler::Fill(const BundleKey& src,
                               perfetto_protos_NetworkPacketEvent* event) {
  perfetto_protos_NetworkPacketEvent_set_direction(
      event,
      src.egress ? perfetto_protos_DIR_EGRESS : perfetto_protos_DIR_INGRESS);
  perfetto_protos_NetworkPacketEvent_set_uid(event, src.uid);
  perfetto_protos_NetworkPacketEvent_set_tag(event, src.tag);

  if (src.tcpFlags.has_value())
    perfetto_protos_NetworkPacketEvent_set_tcp_flags(event, *src.tcpFlags);
  if (src.localPort.has_value())
    perfetto_protos_NetworkPacketEvent_set_local_port(event, *src.localPort);
  if (src.remotePort.has_value())
    perfetto_protos_NetworkPacketEvent_set_remote_port(event, *src.remotePort);
  if (src.icmpType.has_value())
    perfetto_protos_NetworkPacketEvent_set_icmp_type(event, *src.icmpType);
  if (src.icmpCode.has_value())
    perfetto_protos_NetworkPacketEvent_set_icmp_code(event, *src.icmpCode);

  perfetto_protos_NetworkPacketEvent_set_ip_proto(event, src.ipProto);

  char ifname[IF_NAMESIZE] = {};
  if (if_indextoname(src.ifindex, ifname) == ifname) {
    perfetto_protos_NetworkPacketEvent_set_cstr_interface(event, ifname);
  } else {
    perfetto_protos_NetworkPacketEvent_set_cstr_interface(event, "error");
  }
}

void NetworkTraceHandler::FillWithInterning(NetworkTraceState* state,
                                            const BundleKey& key,
                                            const BundleDetails& details,
                                            perfetto_protos_TracePacket* dst) {
  uint64_t iid = 0;
  bool found = false;

  if (state->iids.size() < mInternLimit) {
    auto [iter, success] = state->iids.try_emplace(key, state->iids.size() + 1);
    iid = iter->second;
    found = true;

    if (success) {
      // If we successfully emplaced, record the newly interned data.
      perfetto_protos_InternedData interned_data;
      perfetto_protos_TracePacket_begin_interned_data(dst, &interned_data);
      perfetto_protos_NetworkPacketContext packet_context;
      perfetto_protos_InternedData_begin_packet_context(&interned_data,
                                                        &packet_context);
      perfetto_protos_NetworkPacketEvent event;
      perfetto_protos_NetworkPacketContext_begin_ctx(&packet_context, &event);
      Fill(key, &event);
      perfetto_protos_NetworkPacketContext_end_ctx(&packet_context, &event);
      perfetto_protos_NetworkPacketContext_set_iid(&packet_context, iid);
      perfetto_protos_InternedData_end_packet_context(&interned_data,
                                                      &packet_context);
      perfetto_protos_TracePacket_end_interned_data(dst, &interned_data);
    }
  } else {
    auto iter = state->iids.find(key);
    if (iter != state->iids.end()) {
      iid = iter->second;
      found = true;
    }
  }

  perfetto_protos_NetworkPacketBundle bundle;
  perfetto_protos_TracePacket_begin_network_packet_bundle(dst, &bundle);
  if (found) {
    perfetto_protos_NetworkPacketBundle_set_iid(&bundle, iid);
  } else {
    perfetto_protos_NetworkPacketEvent event;
    perfetto_protos_NetworkPacketBundle_begin_ctx(&bundle, &event);
    Fill(key, &event);
    perfetto_protos_NetworkPacketBundle_end_ctx(&bundle, &event);
  }

  int count = details.time_and_len.size();
  if (!mAggregationThreshold || count < mAggregationThreshold) {
    PerfettoPbPackedMsgUint64 offsets;
    perfetto_protos_NetworkPacketBundle_begin_packet_timestamps(&bundle,
                                                                &offsets);
    for (const auto& [time, length] : details.time_and_len) {
      PerfettoPbPackedMsgUint64Append(&offsets, time - details.minTs);
    }
    perfetto_protos_NetworkPacketBundle_end_packet_timestamps(&bundle,
                                                              &offsets);
    PerfettoPbPackedMsgUint32 lengths;
    perfetto_protos_NetworkPacketBundle_begin_packet_lengths(&bundle, &lengths);
    for (const auto& [time, length] : details.time_and_len) {
      PerfettoPbPackedMsgUint32Append(&lengths, length);
    }
    perfetto_protos_NetworkPacketBundle_end_packet_lengths(&bundle, &lengths);
  } else {
    perfetto_protos_NetworkPacketBundle_set_total_duration(
        &bundle, details.maxTs - details.minTs);
    perfetto_protos_NetworkPacketBundle_set_total_length(&bundle,
                                                         details.bytes);
    perfetto_protos_NetworkPacketBundle_set_total_packets(&bundle, count);
  }
  perfetto_protos_TracePacket_end_network_packet_bundle(dst, &bundle);
}

}  // namespace bpf
}  // namespace android
