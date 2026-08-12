#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#include "core_udp_opus/udp_sender.h"

#include <windows.h>
#include <ws2tcpip.h>

#include <opus/opus.h>

#include <array>
#include <chrono>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace core_udp_opus {

namespace {

constexpr std::size_t kMaxEncodedBytes = 1'500;
constexpr char kMagic[] = {'P', '2', 'A', 'U'};

void write_uint16_be(std::vector<std::uint8_t>& bytes, std::uint16_t value) {
    bytes.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
    bytes.push_back(static_cast<std::uint8_t>(value & 0xFF));
}

void write_uint32_be(std::vector<std::uint8_t>& bytes, std::uint32_t value) {
    bytes.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
    bytes.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
    bytes.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
    bytes.push_back(static_cast<std::uint8_t>(value & 0xFF));
}

void write_uint64_be(std::vector<std::uint8_t>& bytes, std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
        bytes.push_back(static_cast<std::uint8_t>((value >> shift) & 0xFF));
    }
}

std::uint64_t now_ms() {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch())
            .count());
}

}  // namespace

udp_sender::udp_sender() {
    diagnostics_.selected_candidate_pair_type = "udp_opus";
}

udp_sender::~udp_sender() {
    stop_streaming();

    std::lock_guard<std::mutex> lock(mutex_);
    if (winsock_initialized_) {
        WSACleanup();
        winsock_initialized_ = false;
    }
}

start_result udp_sender::start_streaming(const std::string& remote_host, int remote_port) {
    if (remote_host.empty() || remote_port <= 0 || remote_port > 65'535) {
        throw std::invalid_argument("Remote host or port is invalid");
    }

    stop_streaming();

    std::lock_guard<std::mutex> lock(mutex_);
    last_error_message_.clear();
    last_status_message_.clear();
    diagnostics_ = diagnostics{};
    diagnostics_.selected_candidate_pair_type = "udp_opus";
    diagnostics_.local_candidates_count = 1;
    streaming_ = false;
    sequence_ = 0;

    try {
        ensure_winsock_initialized_locked();
        open_socket_locked(remote_host, remote_port);
        last_status_message_ = "Streaming Windows media audio to the Android UDP + Opus receiver.";
        streaming_ = true;
        return start_result{last_status_message_, diagnostics_};
    } catch (...) {
        teardown_streaming_resources_locked();
        throw;
    }
}

void udp_sender::set_application(opus_application application) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (application_ == application) {
        return;
    }

    application_ = application;
    if (encoder_ != nullptr) {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
        encoder_sample_rate_ = 0;
        encoder_channels_ = 0;
    }
}

bool udp_sender::send_pcm16(
    const std::int16_t* pcm,
    std::size_t samples_per_channel,
    int sample_rate,
    int channels,
    std::uint64_t timestamp_ms) {
    if (pcm == nullptr || samples_per_channel == 0 || sample_rate <= 0 || channels < 1 || channels > 2) {
        std::lock_guard<std::mutex> lock(mutex_);
        set_failure_locked("audio_capture_not_supported", "Invalid PCM frame passed to UDP Opus sender");
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (!streaming_ || socket_ == INVALID_SOCKET) {
        return false;
    }

    try {
        initialize_encoder_locked(sample_rate, channels);
    } catch (const std::exception& ex) {
        set_failure_locked("audio_capture_not_supported", ex.what());
        teardown_streaming_resources_locked();
        return false;
    }

    std::array<std::uint8_t, kMaxEncodedBytes> encoded{};
    const auto encoded_bytes = opus_encode(
        encoder_,
        pcm,
        static_cast<int>(samples_per_channel),
        encoded.data(),
        static_cast<opus_int32>(encoded.size()));
    if (encoded_bytes < 0) {
        set_failure_locked("audio_capture_not_supported", std::string("Opus encode failed: ") + opus_strerror(encoded_bytes));
        teardown_streaming_resources_locked();
        return false;
    }

    const auto packet = build_packet(
        sequence_++,
        timestamp_ms == 0 ? now_ms() : timestamp_ms,
        sample_rate,
        channels,
        static_cast<int>(samples_per_channel),
        encoded.data(),
        static_cast<std::size_t>(encoded_bytes));
    const auto send_result = ::send(
        socket_,
        reinterpret_cast<const char*>(packet.data()),
        static_cast<int>(packet.size()),
        0);
    if (send_result == SOCKET_ERROR || send_result != static_cast<int>(packet.size())) {
        set_failure_locked("peer_unreachable", std::string("UDP send failed: ") + describe_socket_error(WSAGetLastError()));
        teardown_streaming_resources_locked();
        return false;
    }

    diagnostics_.failure_hint.clear();
    last_error_message_.clear();
    return true;
}

void udp_sender::stop_streaming() {
    std::lock_guard<std::mutex> lock(mutex_);
    teardown_streaming_resources_locked();
    streaming_ = false;
}

bool udp_sender::is_streaming() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return streaming_;
}

diagnostics udp_sender::get_diagnostics() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return diagnostics_;
}

std::string udp_sender::last_error_message() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_message_;
}

void udp_sender::ensure_winsock_initialized_locked() {
    if (winsock_initialized_) {
        return;
    }

    WSADATA data{};
    const auto result = WSAStartup(MAKEWORD(2, 2), &data);
    if (result != 0) {
        throw std::runtime_error("WSAStartup failed: " + std::to_string(result));
    }
    winsock_initialized_ = true;
}

void udp_sender::initialize_encoder_locked(int sample_rate, int channels) {
    if (encoder_ != nullptr && encoder_sample_rate_ == sample_rate && encoder_channels_ == channels) {
        return;
    }

    if (encoder_ != nullptr) {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
    }

    int error = OPUS_OK;
    const auto application = application_ == opus_application::audio
        ? OPUS_APPLICATION_AUDIO
        : OPUS_APPLICATION_RESTRICTED_LOWDELAY;
    encoder_ = opus_encoder_create(sample_rate, channels, application, &error);
    if (error != OPUS_OK || encoder_ == nullptr) {
        throw std::runtime_error(std::string("Opus encoder initialization failed: ") + opus_strerror(error));
    }

    opus_encoder_ctl(encoder_, OPUS_SET_BITRATE(128'000));
    opus_encoder_ctl(encoder_, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(encoder_, OPUS_SET_COMPLEXITY(10));
    opus_encoder_ctl(encoder_, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder_, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(encoder_, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND));
    encoder_sample_rate_ = sample_rate;
    encoder_channels_ = channels;
}

void udp_sender::open_socket_locked(const std::string& remote_host, int remote_port) {
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_protocol = IPPROTO_UDP;

    addrinfo* results = nullptr;
    const auto port_text = std::to_string(remote_port);
    const auto resolve_result = getaddrinfo(remote_host.c_str(), port_text.c_str(), &hints, &results);
    if (resolve_result != 0 || results == nullptr) {
        throw std::runtime_error("Failed to resolve Android receiver host: " + remote_host);
    }

    std::unique_ptr<addrinfo, decltype(&freeaddrinfo)> results_holder(results, freeaddrinfo);
    socket_ = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket_ == INVALID_SOCKET) {
        throw std::runtime_error("Failed to create UDP socket: " + describe_socket_error(WSAGetLastError()));
    }

    std::memcpy(&remote_address_, results_holder.get()->ai_addr, sizeof(sockaddr_in));
    if (::connect(socket_, reinterpret_cast<const sockaddr*>(&remote_address_), sizeof(remote_address_)) == SOCKET_ERROR) {
        const auto error = WSAGetLastError();
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
        throw std::runtime_error("Failed to connect UDP socket: " + describe_socket_error(error));
    }
}

void udp_sender::teardown_streaming_resources_locked() {
    if (encoder_ != nullptr) {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
        encoder_sample_rate_ = 0;
        encoder_channels_ = 0;
    }

    if (socket_ != INVALID_SOCKET) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
    }
}

void udp_sender::set_failure_locked(std::string failure_hint, std::string message) {
    diagnostics_.failure_hint = std::move(failure_hint);
    last_error_message_ = std::move(message);
    streaming_ = false;
}

std::string udp_sender::describe_socket_error(int error_code) {
    LPSTR message_buffer = nullptr;
    const auto length = FormatMessageA(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr,
        static_cast<DWORD>(error_code),
        MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        reinterpret_cast<LPSTR>(&message_buffer),
        0,
        nullptr);
    std::string message = length > 0 && message_buffer != nullptr
        ? std::string(message_buffer, message_buffer + length)
        : std::to_string(error_code);
    if (message_buffer != nullptr) {
        LocalFree(message_buffer);
    }
    return message;
}

std::vector<std::uint8_t> udp_sender::build_packet(
    std::uint32_t sequence,
    std::uint64_t timestamp_ms,
    int sample_rate,
    int channels,
    int frame_samples_per_channel,
    const std::uint8_t* payload,
    std::size_t payload_size) {
    std::vector<std::uint8_t> packet;
    packet.reserve(26 + payload_size);
    packet.insert(packet.end(), std::begin(kMagic), std::end(kMagic));
    packet.push_back(1);  // version
    packet.push_back(static_cast<std::uint8_t>(channels));
    write_uint16_be(packet, static_cast<std::uint16_t>(frame_samples_per_channel));
    write_uint32_be(packet, static_cast<std::uint32_t>(sample_rate));
    write_uint32_be(packet, sequence);
    write_uint64_be(packet, timestamp_ms);
    write_uint16_be(packet, static_cast<std::uint16_t>(payload_size));
    packet.insert(packet.end(), payload, payload + payload_size);
    return packet;
}

}  // namespace core_udp_opus
