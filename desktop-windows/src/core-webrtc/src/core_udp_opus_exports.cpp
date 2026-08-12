#include "core_udp_opus/core_udp_opus_exports.h"

#include "core_udp_opus/udp_sender.h"

#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <string>

struct core_udp_opus_handle {
    core_udp_opus::udp_sender sender;
};

namespace {

const char* duplicate_string(const std::string& value) {
    const auto size = value.size() + 1;
    auto* buffer = static_cast<char*>(std::malloc(size));
    if (buffer == nullptr) {
        return nullptr;
    }

    std::memcpy(buffer, value.c_str(), size);
    return buffer;
}

core_udp_opus_diagnostics to_export(const core_udp_opus::diagnostics& diagnostics) {
    core_udp_opus_diagnostics exported{};
    exported.path_type = duplicate_string(diagnostics.path_type);
    exported.local_candidates_count = diagnostics.local_candidates_count;
    exported.selected_candidate_pair_type = duplicate_string(diagnostics.selected_candidate_pair_type);
    exported.failure_hint = duplicate_string(diagnostics.failure_hint);
    return exported;
}

core_udp_opus_start_result make_failure(const std::string& message) {
    core_udp_opus_start_result result{};
    result.success = 0;
    result.error_message = duplicate_string(message);
    return result;
}

}  // namespace

extern "C" {

core_udp_opus_handle* core_udp_opus_create() {
    try {
        return new core_udp_opus_handle();
    } catch (...) {
        return nullptr;
    }
}

void core_udp_opus_destroy(core_udp_opus_handle* handle) {
    delete handle;
}

core_udp_opus_start_result core_udp_opus_start_streaming(
    core_udp_opus_handle* handle,
    const char* remote_host,
    int remote_port,
    int application) {
    if (handle == nullptr) {
        return make_failure("UDP audio sender handle was null");
    }
    if (remote_host == nullptr || remote_host[0] == '\0') {
        return make_failure("Remote host was empty");
    }

    try {
        handle->sender.set_application(
            application == 1
                ? core_udp_opus::opus_application::audio
                : core_udp_opus::opus_application::restricted_lowdelay);
        const auto result = handle->sender.start_streaming(remote_host, remote_port);
        core_udp_opus_start_result exported{};
        exported.success = 1;
        exported.status_message = duplicate_string(result.status_message);
        exported.diagnostics = to_export(result.info);
        return exported;
    } catch (const std::exception& ex) {
        return make_failure(ex.what());
    } catch (...) {
        return make_failure("Unknown error starting UDP audio sender");
    }
}

int core_udp_opus_send_pcm16(
    core_udp_opus_handle* handle,
    const std::uint8_t* pcm_bytes,
    int pcm_byte_count,
    int sample_rate,
    int channels,
    int frame_samples_per_channel,
    std::uint64_t timestamp_ms) {
    if (handle == nullptr || pcm_bytes == nullptr || pcm_byte_count <= 0) {
        return 0;
    }
    if (channels < 1 || channels > 2 || frame_samples_per_channel <= 0) {
        return 0;
    }

    const auto expected_bytes = frame_samples_per_channel * channels * static_cast<int>(sizeof(std::int16_t));
    if (pcm_byte_count != expected_bytes) {
        return 0;
    }

    try {
        return handle->sender.send_pcm16(
            reinterpret_cast<const std::int16_t*>(pcm_bytes),
            static_cast<std::size_t>(frame_samples_per_channel),
            sample_rate,
            channels,
            timestamp_ms) ? 1 : 0;
    } catch (...) {
        return 0;
    }
}

void core_udp_opus_stop_streaming(core_udp_opus_handle* handle) {
    if (handle == nullptr) {
        return;
    }

    handle->sender.stop_streaming();
}

int core_udp_opus_is_streaming(core_udp_opus_handle* handle) {
    if (handle == nullptr) {
        return 0;
    }

    return handle->sender.is_streaming() ? 1 : 0;
}

core_udp_opus_diagnostics core_udp_opus_get_diagnostics(core_udp_opus_handle* handle) {
    if (handle == nullptr) {
        return {};
    }

    return to_export(handle->sender.get_diagnostics());
}

int core_udp_opus_has_backend() {
    return 1;
}

void core_udp_opus_free_string(const char* value) {
    std::free(const_cast<char*>(value));
}

}  // extern "C"
