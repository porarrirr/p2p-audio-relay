#pragma once

#include <cstdint>
#include <cstddef>

#ifdef _WIN32
#define CORE_UDP_OPUS_EXPORT __declspec(dllexport)
#else
#define CORE_UDP_OPUS_EXPORT
#endif

extern "C" {

struct core_udp_opus_handle;

struct core_udp_opus_diagnostics {
    const char* path_type;
    int local_candidates_count;
    const char* selected_candidate_pair_type;
    const char* failure_hint;
};

struct core_udp_opus_start_result {
    int success;
    const char* error_message;
    const char* status_message;
    core_udp_opus_diagnostics diagnostics;
};

CORE_UDP_OPUS_EXPORT core_udp_opus_handle* core_udp_opus_create();
CORE_UDP_OPUS_EXPORT void core_udp_opus_destroy(core_udp_opus_handle* handle);
CORE_UDP_OPUS_EXPORT core_udp_opus_start_result core_udp_opus_start_streaming(
    core_udp_opus_handle* handle,
    const char* remote_host,
    int remote_port,
    int application
);
CORE_UDP_OPUS_EXPORT int core_udp_opus_send_pcm16(
    core_udp_opus_handle* handle,
    const std::uint8_t* pcm_bytes,
    int pcm_byte_count,
    int sample_rate,
    int channels,
    int frame_samples_per_channel,
    std::uint64_t timestamp_ms
);
CORE_UDP_OPUS_EXPORT void core_udp_opus_stop_streaming(core_udp_opus_handle* handle);
CORE_UDP_OPUS_EXPORT int core_udp_opus_is_streaming(core_udp_opus_handle* handle);
CORE_UDP_OPUS_EXPORT core_udp_opus_diagnostics core_udp_opus_get_diagnostics(core_udp_opus_handle* handle);
CORE_UDP_OPUS_EXPORT int core_udp_opus_has_backend();
CORE_UDP_OPUS_EXPORT void core_udp_opus_free_string(const char* value);

}  // extern "C"
