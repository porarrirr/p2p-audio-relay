#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <winsock2.h>

struct OpusEncoder;

namespace core_udp_opus {

enum class opus_application {
    restricted_lowdelay = 0,
    audio = 1
};

struct diagnostics {
    std::string path_type;
    int local_candidates_count = 0;
    std::string selected_candidate_pair_type;
    std::string failure_hint;
};

struct start_result {
    std::string status_message;
    diagnostics info;
};

class udp_sender {
public:
    udp_sender();
    ~udp_sender();

    start_result start_streaming(const std::string& remote_host, int remote_port);
    void set_application(opus_application application);
    bool send_pcm16(
        const std::int16_t* pcm,
        std::size_t samples_per_channel,
        int sample_rate,
        int channels,
        std::uint64_t timestamp_ms
    );
    void stop_streaming();
    bool is_streaming() const;
    diagnostics get_diagnostics() const;
    std::string last_error_message() const;

private:
    void ensure_winsock_initialized_locked();
    void initialize_encoder_locked(int sample_rate, int channels);
    void open_socket_locked(const std::string& remote_host, int remote_port);
    void teardown_streaming_resources_locked();
    void set_failure_locked(std::string failure_hint, std::string message);
    static std::string describe_socket_error(int error_code);
    static std::vector<std::uint8_t> build_packet(
        std::uint32_t sequence,
        std::uint64_t timestamp_ms,
        int sample_rate,
        int channels,
        int frame_samples_per_channel,
        const std::uint8_t* payload,
        std::size_t payload_size
    );

    mutable std::mutex mutex_;
    diagnostics diagnostics_;
    std::string last_error_message_;
    std::string last_status_message_;
    SOCKET socket_ = INVALID_SOCKET;
    sockaddr_in remote_address_{};
    OpusEncoder* encoder_ = nullptr;
    bool winsock_initialized_ = false;
    bool streaming_ = false;
    std::uint32_t sequence_ = 0;
    int encoder_sample_rate_ = 0;
    int encoder_channels_ = 0;
    opus_application application_ = opus_application::restricted_lowdelay;
};

}  // namespace core_udp_opus
