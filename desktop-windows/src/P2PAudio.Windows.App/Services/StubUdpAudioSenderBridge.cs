using P2PAudio.Windows.Core.Audio;
using P2PAudio.Windows.Core.Models;
using P2PAudio.Windows.Core.Networking;
using P2PAudio.Windows.Core.Protocol;

namespace P2PAudio.Windows.App.Services;

public sealed class StubUdpAudioSenderBridge : IUdpAudioSenderBridge
{
    private readonly string _startupReason;

    public StubUdpAudioSenderBridge(string startupReason)
    {
        _startupReason = startupReason;
    }

    public TransportMode Mode => TransportMode.UdpOpus;

    public bool IsNativeBackend => false;

    public bool IsStreaming => false;

    public Task<UdpAudioSenderResult> StartStreamingAsync(
        string remoteHost,
        int remotePort,
        string remoteServiceName,
        UdpOpusApplication application)
    {
        _ = remoteHost;
        _ = remotePort;
        _ = remoteServiceName;
        _ = application;
        return Task.FromResult(
            new UdpAudioSenderResult(
                Success: false,
                ErrorMessage: "UDP + Opus 送信モジュールを利用できません。",
                StatusMessage: "UDP + Opus の送信を開始できませんでした。",
                Diagnostics: CreateDiagnostics()
            )
        );
    }

    public void StopStreaming()
    {
    }

    public bool SendPcmFrame(PcmFrame frame)
    {
        _ = frame;
        return false;
    }

    public ConnectionDiagnostics GetDiagnostics()
    {
        return CreateDiagnostics();
    }

    public BridgeBackendHealth GetBackendHealth()
    {
        return new BridgeBackendHealth(
            IsReady: false,
            IsDevelopmentStub: false,
            Message: $"UDP + Opus 送信モジュールを利用できません。{_startupReason}",
            BlockingFailureCode: FailureCode.WebRtcNegotiationFailed
        );
    }

    public void Close()
    {
    }

    private static ConnectionDiagnostics CreateDiagnostics()
    {
        const string failureHint = "native_backend_unavailable";
        return new ConnectionDiagnostics(
            PathType: UsbTetheringDetector.ClassifyPrimaryPath(),
            LocalCandidatesCount: 0,
            SelectedCandidatePairType: "udp_opus",
            FailureHint: failureHint,
            NormalizedFailureCode: FailureCodeMapper.FromFailureHint(failureHint)
        );
    }
}
