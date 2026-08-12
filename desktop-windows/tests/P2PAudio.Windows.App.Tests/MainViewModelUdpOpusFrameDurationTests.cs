using P2PAudio.Windows.App.Services;
using P2PAudio.Windows.App.ViewModels;
using P2PAudio.Windows.Core.Audio;
using P2PAudio.Windows.Core.Models;

namespace P2PAudio.Windows.App.Tests;

public sealed class MainViewModelUdpOpusFrameDurationTests
{
    [Fact]
    public void UdpOpusFrameDuration_DefaultsToTwentyMilliseconds()
    {
        var viewModel = CreateViewModel();

        Assert.Equal(20, viewModel.SelectedUdpOpusFrameDurationMs);
        Assert.Equal(2, viewModel.SelectedUdpOpusFrameDurationIndex);
        Assert.Contains("20 ms", viewModel.UdpOpusFrameDurationSummary);
        viewModel.Shutdown();
    }

    [Fact]
    public void SelectUdpOpusFrameDuration_WhenIdle_UpdatesSelection()
    {
        var viewModel = CreateViewModel();
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        viewModel.SelectUdpOpusFrameDuration(10);

        Assert.Equal(10, viewModel.SelectedUdpOpusFrameDurationMs);
        Assert.Equal(1, viewModel.SelectedUdpOpusFrameDurationIndex);
        Assert.Contains("バランス", viewModel.UdpOpusFrameDurationDescription);
        viewModel.Shutdown();
    }

    [Fact]
    public void SelectUdpOpusFrameDuration_AllowsFortyAndSixtyMilliseconds()
    {
        var viewModel = CreateViewModel();
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        viewModel.SelectUdpOpusFrameDuration(40);
        Assert.Equal(40, viewModel.SelectedUdpOpusFrameDurationMs);
        Assert.Equal(3, viewModel.SelectedUdpOpusFrameDurationIndex);

        viewModel.SelectUdpOpusFrameDuration(60);
        Assert.Equal(60, viewModel.SelectedUdpOpusFrameDurationMs);
        Assert.Equal(4, viewModel.SelectedUdpOpusFrameDurationIndex);
        Assert.Contains("最大", viewModel.UdpOpusFrameDurationDescription);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SelectUdpOpusFrameDuration_WhenFlowActive_DoesNotChangeSelection()
    {
        var viewModel = CreateViewModel();
        viewModel.SelectTransportMode(TransportMode.UdpOpus);
        await viewModel.StartSenderAsync();

        viewModel.SelectUdpOpusFrameDuration(5);

        Assert.Equal(20, viewModel.SelectedUdpOpusFrameDurationMs);
        viewModel.Shutdown();
    }

    private static MainViewModel CreateViewModel()
    {
        return new MainViewModel(
            new FakeWebRtcBridge(),
            new FakeUdpAudioSenderBridge(),
            new FakeUdpReceiverDiscoveryService(),
            new FakeConnectionCodeSessionFactory(),
            initializeImmediately: true,
            webRtcLoopbackSenderFactory: static () => new FakeLoopbackAudioSender(),
            udpLoopbackSenderFactory: static () => new FakeLoopbackAudioSender()
        );
    }

    private sealed class FakeWebRtcBridge : IWebRtcBridge, IDisposable
    {
        public TransportMode Mode => TransportMode.WebRtc;
        public bool IsNativeBackend => true;
        public WebRtcOfferResult OfferResult { get; set; } = new(
            Success: true,
            ErrorMessage: string.Empty,
            SessionId: "session-1",
            OfferSdp: "v=0\r\ns=offer\r\n",
            Fingerprint: "sender-fp",
            Diagnostics: new ConnectionDiagnostics()
        );

        public BridgeBackendHealth BackendHealth { get; set; } = new(
            IsReady: true,
            IsDevelopmentStub: false,
            Message: "ネイティブ接続モジュールを利用できます。",
            BlockingFailureCode: null
        );

        public Task<WebRtcOfferResult> CreateOfferAsync() => Task.FromResult(OfferResult);
        public Task<WebRtcAnswerResult> CreateAnswerAsync(string offerSdp) => Task.FromResult(new WebRtcAnswerResult(true, "", "", "", new ConnectionDiagnostics()));
        public Task<WebRtcOperationResult> ApplyAnswerAsync(string answerSdp) => Task.FromResult(new WebRtcOperationResult(true, "", "", new ConnectionDiagnostics()));
        public bool SendPcmPacket(byte[] packet) => true;
        public bool TryReceivePcmPacket(out byte[] packet)
        {
            packet = [];
            return false;
        }
        public ConnectionDiagnostics GetDiagnostics() => new();
        public BridgeBackendHealth GetBackendHealth() => BackendHealth;
        public void Close()
        {
        }
        public void Dispose()
        {
        }
    }

    private sealed class FakeUdpAudioSenderBridge : IUdpAudioSenderBridge, IDisposable
    {
        public TransportMode Mode => TransportMode.UdpOpus;
        public bool IsNativeBackend => true;
        public bool IsStreaming { get; private set; }

        public Task<UdpAudioSenderResult> StartStreamingAsync(string remoteHost, int remotePort, string remoteServiceName)
        {
            _ = remoteHost;
            _ = remotePort;
            _ = remoteServiceName;
            IsStreaming = true;
            return Task.FromResult(new UdpAudioSenderResult(true, "", "", new ConnectionDiagnostics()));
        }

        public bool SendPcmFrame(PcmFrame frame)
        {
            _ = frame;
            return true;
        }

        public void StopStreaming()
        {
            IsStreaming = false;
        }

        public ConnectionDiagnostics GetDiagnostics() => new();
        public BridgeBackendHealth GetBackendHealth() => new(true, false, "UDP + Opus 送信モジュールを利用できます。", null);
        public void Close()
        {
        }
        public void Dispose()
        {
        }
    }

    private sealed class FakeLoopbackAudioSender : ILoopbackAudioSender
    {
        public bool IsRunning { get; private set; }
        public event EventHandler<LoopbackAudioDiagnostics>? DiagnosticsChanged
        {
            add { }
            remove { }
        }

        public void Start()
        {
            IsRunning = true;
        }

        public void Stop()
        {
            IsRunning = false;
        }

        public void Dispose()
        {
            Stop();
        }
    }

    private sealed class FakeUdpReceiverDiscoveryService : IUdpReceiverDiscoveryService
    {
        public Task<IReadOnlyList<UdpReceiverEndpoint>> DiscoverAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult<IReadOnlyList<UdpReceiverEndpoint>>([]);
        }
    }

    private sealed class FakeConnectionCodeSessionFactory : IConnectionCodeSessionFactory
    {
        public IConnectionCodeSession Create(string initPayload, string localAddressHintSource, long expiresAtUnixMs)
        {
            _ = initPayload;
            _ = localAddressHintSource;
            return new FakeConnectionCodeSession(expiresAtUnixMs);
        }
    }

    private sealed class FakeConnectionCodeSession : IConnectionCodeSession
    {
        private readonly TaskCompletionSource<ConnectionCodeSubmission> _confirmPayloadTcs =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public FakeConnectionCodeSession(long expiresAtUnixMs)
        {
            ExpiresAtUnixMs = expiresAtUnixMs;
        }

        public string ConnectionCode => "p2paudio-c1:127.0.0.1:12345:1:token";
        public long ExpiresAtUnixMs { get; }

        public Task<ConnectionCodeSubmission> WaitForConfirmPayloadAsync(CancellationToken cancellationToken)
        {
            return _confirmPayloadTcs.Task.WaitAsync(cancellationToken);
        }

        public void Dispose()
        {
            _confirmPayloadTcs.TrySetCanceled();
        }
    }
}
