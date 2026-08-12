using P2PAudio.Windows.Core.Audio;
using P2PAudio.Windows.Core.Models;

namespace P2PAudio.Windows.App.Services;

public enum UdpOpusApplication
{
    RestrictedLowDelay = 0,
    Audio = 1
}

public interface IUdpAudioSenderBridge : IAudioTransportBackend
{
    Task<UdpAudioSenderResult> StartStreamingAsync(
        string remoteHost,
        int remotePort,
        string remoteServiceName,
        UdpOpusApplication application);
    bool SendPcmFrame(PcmFrame frame);
    void StopStreaming();
    bool IsStreaming { get; }
}

public sealed record UdpAudioSenderResult(
    bool Success,
    string ErrorMessage,
    string StatusMessage,
    ConnectionDiagnostics Diagnostics
);
