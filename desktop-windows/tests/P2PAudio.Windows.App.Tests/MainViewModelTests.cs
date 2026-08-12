using P2PAudio.Windows.App.Services;
using P2PAudio.Windows.App.ViewModels;
using P2PAudio.Windows.Core.Audio;
using P2PAudio.Windows.Core.Models;
using P2PAudio.Windows.Core.Protocol;

namespace P2PAudio.Windows.App.Tests;

public sealed class MainViewModelTests
{
    private static long FutureExpiry => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 600_000;
    private static long PastExpiry => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 1;

    private static string MakeInitPayload(string sessionId = "session-1", long? expiresAtUnixMs = null) =>
        QrPayloadCodec.EncodeInit(PairingInitPayload.Create(
            sessionId: sessionId,
            senderDeviceName: "sender",
            senderPubKeyFingerprint: "sender-fp",
            offerSdp: "v=0\r\ns=offer\r\n",
            expiresAtUnixMs: expiresAtUnixMs ?? FutureExpiry
        ));

    private static string MakeConfirmPayload(string sessionId = "session-1", long? expiresAtUnixMs = null) =>
        QrPayloadCodec.EncodeConfirm(PairingConfirmPayload.Create(
            sessionId: sessionId,
            receiverDeviceName: "receiver",
            receiverPubKeyFingerprint: "receiver-fp",
            answerSdp: "v=0\r\ns=answer\r\n",
            expiresAtUnixMs: expiresAtUnixMs ?? FutureExpiry
        ));

    private static string MakeUdpConfirmPayload(string sessionId, int receiverPort = 49_152, long? expiresAtUnixMs = null) =>
        QrPayloadCodec.EncodeUdpConfirm(UdpConfirmPayload.Create(
            sessionId: sessionId,
            receiverDeviceName: "android-receiver",
            receiverPort: receiverPort,
            expiresAtUnixMs: expiresAtUnixMs ?? FutureExpiry
        ));

    private static byte[] MakeValidPcmPacket(int sequence = 0) =>
        PcmPacketCodec.Encode(new PcmFrame(
            Sequence: sequence,
            TimestampMs: 123_456,
            SampleRate: 48_000,
            Channels: 2,
            BitsPerSample: 16,
            FrameSamplesPerChannel: 960,
            PcmBytes: Enumerable.Repeat((byte)0x2A, 3840).ToArray()
        ));

    private static MainViewModel CreateViewModel(
        FakeWebRtcBridge? bridge = null,
        FakeConnectionCodeSessionFactory? connectionCodeSessionFactory = null,
        FakeUdpAudioSenderBridge? udpBridge = null,
        FakeUdpReceiverDiscoveryService? udpReceiverDiscoveryService = null,
        FakeLoopbackAudioSender? webRtcLoopbackSender = null,
        FakeLoopbackAudioSender? udpLoopbackSender = null) =>
        new(
            bridge ?? new FakeWebRtcBridge(),
            udpBridge ?? new FakeUdpAudioSenderBridge(),
            udpReceiverDiscoveryService ?? new FakeUdpReceiverDiscoveryService(),
            connectionCodeSessionFactory ?? new FakeConnectionCodeSessionFactory(),
            initializeImmediately: true,
            webRtcLoopbackSenderFactory: () => webRtcLoopbackSender ?? new FakeLoopbackAudioSender(),
            udpLoopbackSenderFactory: () => udpLoopbackSender ?? new FakeLoopbackAudioSender()
        );

    // --- Backend readiness ---

    [Fact]
    public async Task StartSender_WhenBackendIsNotReady_StaysFailed()
    {
        var bridge = new FakeWebRtcBridge
        {
            BackendHealth = new BridgeBackendHealth(
                IsReady: false,
                IsDevelopmentStub: false,
                Message: "Native runtime missing.",
                BlockingFailureCode: FailureCode.WebRtcNegotiationFailed
            )
        };
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();

        Assert.Equal(StreamState.Failed, viewModel.CurrentStreamState);
        Assert.Contains("接続モジュールが利用できません", viewModel.StatusMessage);
        Assert.Contains("webrtc_negotiation_failed", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public void StartListener_WhenBackendIsNotReady_StaysFailed()
    {
        var bridge = new FakeWebRtcBridge
        {
            BackendHealth = new BridgeBackendHealth(
                IsReady: false,
                IsDevelopmentStub: false,
                Message: "Native runtime missing.",
                BlockingFailureCode: FailureCode.WebRtcNegotiationFailed
            )
        };
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();

        Assert.Equal(StreamState.Failed, viewModel.CurrentStreamState);
        Assert.Contains("接続モジュールが利用できません", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task InitializeAsync_WhenDeferred_EnablesStartupActions()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = new MainViewModel(
            bridge,
            new FakeConnectionCodeSessionFactory(),
            initializeImmediately: false
        );

        Assert.Equal("内部処理を初期化しています...", viewModel.BackendLabel);
        Assert.False(viewModel.CanStartSender);
        Assert.False(viewModel.CanStartListener);

        await viewModel.InitializeAsync();

        Assert.Equal("内部処理: WebRTC ネイティブ接続モジュール", viewModel.BackendLabel);
        Assert.Equal("ネイティブ接続モジュールを利用できます。", viewModel.StatusMessage);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.True(viewModel.CanStartSender);
        Assert.True(viewModel.CanStartListener);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task StartSender_WhenDeferredStartupIncomplete_RemainsBlocked()
    {
        var bridge = new FakeWebRtcBridge();
        var udpBridge = new FakeUdpAudioSenderBridge();
        var viewModel = new MainViewModel(
            bridge,
            udpBridge,
            new FakeUdpReceiverDiscoveryService(),
            new FakeConnectionCodeSessionFactory(),
            initializeImmediately: false
        );
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        Assert.False(viewModel.CanStartSender);
        Assert.Contains("初期化", viewModel.RecommendedAction);

        await viewModel.StartSenderAsync();

        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Equal(SetupStep.Entry, viewModel.CurrentSetupStep);
        Assert.Empty(viewModel.CurrentConnectionCode);
        Assert.Contains("起動準備", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task InitializeAsync_WhenBackendProbeThrows_LeavesStartupBlockedState()
    {
        var bridge = new FakeWebRtcBridge
        {
            BackendHealthException = new InvalidOperationException("probe_failed")
        };
        var viewModel = new MainViewModel(
            bridge,
            new FakeConnectionCodeSessionFactory(),
            initializeImmediately: false
        );

        await viewModel.InitializeAsync();

        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Contains("接続モジュールが利用できません", viewModel.StatusMessage);
        Assert.Contains("probe_failed", viewModel.StatusMessage);
        Assert.Contains("webrtc_negotiation_failed", viewModel.FailureCodeLabel);
        Assert.Contains("必要なランタイム", viewModel.RecommendedAction);
        Assert.Equal(1, bridge.DisposeCalls);
        Assert.False(viewModel.CanStartSender);
        Assert.False(viewModel.CanStartListener);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task InitializeAsync_WhenUdpSelectedAndWebRtcUnavailable_UsesUdpHealthMessaging()
    {
        var webRtcBridge = new FakeWebRtcBridge
        {
            BackendHealth = new BridgeBackendHealth(
                IsReady: false,
                IsDevelopmentStub: false,
                Message: "Native runtime missing.",
                BlockingFailureCode: FailureCode.WebRtcNegotiationFailed
            )
        };
        var udpBridge = new FakeUdpAudioSenderBridge();
        var viewModel = new MainViewModel(
            webRtcBridge,
            udpBridge,
            new FakeUdpReceiverDiscoveryService(),
            new FakeConnectionCodeSessionFactory(),
            initializeImmediately: false
        );
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        await viewModel.InitializeAsync();

        Assert.Equal("内部処理: UDP + Opus ネイティブ送信モジュール", viewModel.BackendLabel);
        Assert.Equal("UDP + Opus 送信モジュールを利用できます。", viewModel.StatusMessage);
        Assert.DoesNotContain("接続モジュールが利用できません", viewModel.StatusMessage);
        Assert.Contains("Windows のメディア音声送信", viewModel.RecommendedAction);
        Assert.True(viewModel.CanStartSender);
        Assert.False(viewModel.CanStartListener);
        viewModel.Shutdown();
    }

    // --- Invalid / malformed payloads ---

    [Fact]
    public async Task ProcessInputPayload_InvalidPayload_RestartsToEntry()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.ProcessInputPayloadAsync("p2paudio-z1:invalid");

        Assert.Equal("案内: 最初からやり直し", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Contains("invalid_payload", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task ProcessInputPayload_EmptyString_NoStateChange()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.ProcessInputPayloadAsync("");

        Assert.Equal("案内: 最初の選択", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Contains("空です", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    // --- Expired payload ---

    [Fact]
    public async Task ProcessInputPayload_ExpiredInitPayload_RestartsToEntry()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);
        viewModel.StartListener();

        var expired = MakeInitPayload(expiresAtUnixMs: PastExpiry);
        await viewModel.ProcessInputPayloadAsync(expired);

        Assert.Equal("案内: 最初からやり直し", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Contains("session_expired", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task ProcessInputPayload_ExpiredConfirmPayload_RestartsToEntry()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        Assert.Equal("案内: 開始データを共有", viewModel.FlowStateLabel);

        var expired = MakeConfirmPayload(expiresAtUnixMs: PastExpiry);
        await viewModel.ProcessInputPayloadAsync(expired);

        Assert.Equal("案内: 最初からやり直し", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Contains("session_expired", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    // --- Session ID mismatch ---

    [Fact]
    public async Task ProcessInputPayload_SessionIdMismatch_RestartsToEntry()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        Assert.Equal("案内: 開始データを共有", viewModel.FlowStateLabel);

        var mismatch = MakeConfirmPayload(sessionId: "different-session");
        await viewModel.ProcessInputPayloadAsync(mismatch);

        Assert.Equal("案内: 最初からやり直し", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.Contains("invalid_payload", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    // --- Offer / answer failures ---

    [Fact]
    public async Task StartSender_OfferFailure_MarksFailedState()
    {
        var bridge = new FakeWebRtcBridge
        {
            OfferResult = new WebRtcOfferResult(
                Success: false,
                ErrorMessage: "create_peer_connection_failed",
                SessionId: string.Empty,
                OfferSdp: string.Empty,
                Fingerprint: string.Empty,
                Diagnostics: new ConnectionDiagnostics(
                    FailureHint: "create_peer_connection_failed",
                    NormalizedFailureCode: FailureCode.WebRtcNegotiationFailed
                )
            )
        };
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();

        Assert.Equal(StreamState.Failed, viewModel.CurrentStreamState);
        Assert.Contains("開始データの作成に失敗しました", viewModel.StatusMessage);
        Assert.Contains("webrtc_negotiation_failed", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task ListenerProcessInit_AnswerFailure_MarksFailedState()
    {
        var bridge = new FakeWebRtcBridge
        {
            AnswerResult = new WebRtcAnswerResult(
                Success: false,
                ErrorMessage: "set_remote_offer_failed",
                AnswerSdp: string.Empty,
                Fingerprint: string.Empty,
                Diagnostics: new ConnectionDiagnostics(
                    FailureHint: "set_remote_offer_failed",
                    NormalizedFailureCode: FailureCode.WebRtcNegotiationFailed
                )
            )
        };
        var viewModel = CreateViewModel(bridge);
        viewModel.StartListener();

        await viewModel.ProcessInputPayloadAsync(MakeInitPayload());

        Assert.Equal(StreamState.Failed, viewModel.CurrentStreamState);
        Assert.Contains("応答データの作成に失敗しました", viewModel.StatusMessage);
        Assert.Contains("webrtc_negotiation_failed", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SenderApplyAnswer_Failure_MarksFailedState()
    {
        var bridge = new FakeWebRtcBridge
        {
            ApplyResult = new WebRtcOperationResult(
                Success: false,
                ErrorMessage: "set_remote_answer_failed",
                StatusMessage: "failed",
                Diagnostics: new ConnectionDiagnostics(
                    FailureHint: "set_remote_answer_failed",
                    NormalizedFailureCode: FailureCode.WebRtcNegotiationFailed
                )
            )
        };
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());
        Assert.True(viewModel.IsVerificationPending);

        await viewModel.ApproveVerificationAndConnectAsync();

        Assert.Equal(StreamState.Failed, viewModel.CurrentStreamState);
        Assert.Contains("応答データの適用に失敗しました", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    // --- Verification reject ---

    [Fact]
    public async Task RejectVerification_RestartsToEntry()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());
        Assert.True(viewModel.IsVerificationPending);
        Assert.Equal("案内: 6桁コードを確認", viewModel.FlowStateLabel);

        viewModel.RejectVerificationAndRestart();

        Assert.Equal("案内: 最初からやり直し", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Idle, viewModel.CurrentStreamState);
        Assert.False(viewModel.IsVerificationPending);
        Assert.Contains("invalid_payload", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    // --- Stop ---

    [Fact]
    public async Task Stop_ResetsAllState()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        Assert.Equal("案内: 開始データを共有", viewModel.FlowStateLabel);
        Assert.NotEmpty(viewModel.CurrentPayload);

        viewModel.Stop();

        Assert.Equal("案内: 最初の選択", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Ended, viewModel.CurrentStreamState);
        Assert.Empty(viewModel.CurrentPayload);
        Assert.Empty(viewModel.VerificationCode);
        Assert.Empty(viewModel.ActiveSessionId);
        Assert.False(viewModel.IsVerificationPending);
        Assert.Equal("接続を終了しました。", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    // --- Listener full flow ---

    [Fact]
    public async Task ProcessInputPayload_WhenListenerAlreadyHasConfirm_KeepsListenerState()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);
        var initRaw = MakeInitPayload();

        viewModel.StartListener();
        await viewModel.ProcessInputPayloadAsync(initRaw);
        Assert.Equal("案内: 応答データを共有", viewModel.FlowStateLabel);

        await viewModel.ProcessInputPayloadAsync(initRaw);

        Assert.Equal("案内: 応答データを共有", viewModel.FlowStateLabel);
        Assert.Contains("すでに作成済み", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task ListenerReceiveLoop_FirstPacketTransitionsConnectingToStreaming()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();
        await viewModel.ProcessInputPayloadAsync(MakeInitPayload());
        Assert.Equal(StreamState.Connecting, viewModel.CurrentStreamState);

        bridge.EnqueueIncomingPacket(MakeValidPcmPacket());
        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));

        Assert.Equal("案内: 接続済み", viewModel.FlowStateLabel);
        Assert.Contains("相手の音声を受信しています", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task ListenerFullFlow_GeneratesVerificationCode()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();
        await viewModel.ProcessInputPayloadAsync(MakeInitPayload());

        Assert.Equal("案内: 応答データを共有", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Connecting, viewModel.CurrentStreamState);
        Assert.NotEmpty(viewModel.VerificationCode);
        Assert.Equal(6, viewModel.VerificationCode.Length);
        Assert.NotEmpty(viewModel.CurrentPayload);
        Assert.Equal("session-1", viewModel.ActiveSessionId);
        viewModel.Shutdown();
    }

    // --- Sender full flow ---

    [Fact]
    public async Task SenderFullFlow_OfferThroughVerifyToConnect()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        Assert.Equal("案内: 開始データを共有", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Capturing, viewModel.CurrentStreamState);
        Assert.NotEmpty(viewModel.CurrentPayload);
        Assert.Equal("session-1", viewModel.ActiveSessionId);

        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());
        Assert.Equal("案内: 6桁コードを確認", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Connecting, viewModel.CurrentStreamState);
        Assert.True(viewModel.IsVerificationPending);
        Assert.NotEmpty(viewModel.VerificationCode);
        Assert.Equal(6, viewModel.VerificationCode.Length);

        await viewModel.ApproveVerificationAndConnectAsync();
        Assert.Equal("案内: 接続中", viewModel.FlowStateLabel);
        Assert.Equal(StreamState.Streaming, viewModel.CurrentStreamState);
        Assert.False(viewModel.IsVerificationPending);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task StartSender_GeneratesConnectionCode()
    {
        var bridge = new FakeWebRtcBridge();
        var connectionCodeFactory = new FakeConnectionCodeSessionFactory();
        var viewModel = CreateViewModel(bridge, connectionCodeFactory);

        await viewModel.StartSenderAsync();

        Assert.StartsWith(ConnectionCodeCodec.Prefix, viewModel.CurrentConnectionCode);
        Assert.Contains("接続コードを作成しました", viewModel.StatusMessage);
        Assert.NotNull(connectionCodeFactory.ActiveSession);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SenderFlow_ConnectionCodeConfirm_AutoConnects()
    {
        var bridge = new FakeWebRtcBridge();
        var connectionCodeFactory = new FakeConnectionCodeSessionFactory();
        var viewModel = CreateViewModel(bridge, connectionCodeFactory);

        await viewModel.StartSenderAsync();
        connectionCodeFactory.ActiveSession!.CompleteConfirm(MakeConfirmPayload());

        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));

        Assert.Equal(StreamState.Streaming, viewModel.CurrentStreamState);
        Assert.False(viewModel.IsVerificationPending);
        Assert.Empty(viewModel.CurrentConnectionCode);
        Assert.NotEmpty(viewModel.VerificationCode);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SenderVerificationCode_MatchesListenerVerificationCode()
    {
        var senderBridge = new FakeWebRtcBridge();
        var listenerBridge = new FakeWebRtcBridge();

        var sender = CreateViewModel(senderBridge);
        var listener = CreateViewModel(listenerBridge);

        await sender.StartSenderAsync();
        listener.StartListener();
        await listener.ProcessInputPayloadAsync(MakeInitPayload());

        await sender.ProcessInputPayloadAsync(MakeConfirmPayload());

        Assert.Equal(sender.VerificationCode, listener.VerificationCode);
        Assert.NotEmpty(sender.VerificationCode);

        sender.Shutdown();
        listener.Shutdown();
    }

    [Fact]
    public void SelectTransportMode_Udp_DisablesListenerAction()
    {
        var viewModel = CreateViewModel();

        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        Assert.Equal(TransportMode.UdpOpus, viewModel.SelectedTransportMode);
        Assert.False(viewModel.CanStartListener);
        Assert.True(viewModel.CanStartSender);
        Assert.Contains("メディア音声", viewModel.TransportModeDescription);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task StartSender_UdpMode_GeneratesConnectionCode()
    {
        var viewModel = CreateViewModel(
            udpBridge: new FakeUdpAudioSenderBridge(),
            connectionCodeSessionFactory: new FakeConnectionCodeSessionFactory()
        );
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        await viewModel.StartSenderAsync();

        Assert.Equal(StreamState.Connecting, viewModel.CurrentStreamState);
        Assert.Equal("案内: 接続コードを共有", viewModel.FlowStateLabel);
        Assert.StartsWith(ConnectionCodeCodec.Prefix, viewModel.CurrentConnectionCode);
        Assert.Contains("接続コード", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SenderFlow_UdpConnectionCodeConfirm_AutoConnects()
    {
        var udpBridge = new FakeUdpAudioSenderBridge();
        var udpLoopbackSender = new FakeLoopbackAudioSender();
        var connectionCodeFactory = new FakeConnectionCodeSessionFactory();
        var viewModel = CreateViewModel(
            udpBridge: udpBridge,
            connectionCodeSessionFactory: connectionCodeFactory,
            udpLoopbackSender: udpLoopbackSender
        );
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        await viewModel.StartSenderAsync();
        var sessionId = QrPayloadCodec.DecodeUdpInit(viewModel.CurrentPayload).SessionId;
        connectionCodeFactory.ActiveSession!.CompleteConfirm(
            MakeUdpConfirmPayload(sessionId),
            remoteAddress: "192.168.10.42"
        );

        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));

        Assert.Equal(StreamState.Streaming, viewModel.CurrentStreamState);
        Assert.Equal("案内: UDP + Opus 送信中", viewModel.FlowStateLabel);
        Assert.Equal("192.168.10.42", udpBridge.LastHost);
        Assert.Equal(49_152, udpBridge.LastPort);
        Assert.Equal(1, udpLoopbackSender.StartCalls);
        Assert.True(udpLoopbackSender.IsRunning);
        Assert.Empty(viewModel.CurrentConnectionCode);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SenderDiagnostics_LoopbackStats_AreDisplayed()
    {
        var bridge = new FakeWebRtcBridge();
        var loopbackSender = new FakeLoopbackAudioSender();
        var viewModel = CreateViewModel(
            bridge: bridge,
            webRtcLoopbackSender: loopbackSender
        );

        await viewModel.StartSenderAsync();
        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());
        await viewModel.ApproveVerificationAndConnectAsync();

        loopbackSender.EmitDiagnostics(new LoopbackAudioDiagnostics(
            IsActive: true,
            SampleRate: 48_000,
            Channels: 2,
            BitsPerSample: 16,
            FrameSamplesPerChannel: 960,
            FrameDurationMs: 20,
            FramesPerSecond: 50,
            PendingPcmBytes: 3_840,
            PendingSendFrames: 2,
            SentFrames: 24,
            SendFailures: 1,
            PendingSendDrops: 3,
            PacingEnabled: true
        ));

        Assert.Contains("48,000", viewModel.AudioStreamSummaryLabel);
        Assert.Contains("ステレオ", viewModel.AudioStreamSummaryLabel);
        Assert.Contains("送信待ち 2", viewModel.AudioBufferingLabel);
        Assert.Contains("失敗 1", viewModel.AudioHealthLabel);
        Assert.Contains("キュー整理 3", viewModel.AudioHealthLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task StartSender_UdpMode_ConnectionCodeFailure_Fails()
    {
        var connectionCodeFactory = new FakeConnectionCodeSessionFactory
        {
            CreateException = new SessionFailure(FailureCode.NetworkInterfaceNotUsable, "no_interface")
        };
        var viewModel = CreateViewModel(
            udpBridge: new FakeUdpAudioSenderBridge(),
            connectionCodeSessionFactory: connectionCodeFactory
        );
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        await viewModel.StartSenderAsync();

        Assert.Equal(StreamState.Failed, viewModel.CurrentStreamState);
        Assert.Contains("no_interface", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    // --- Stream health monitoring ---

    [Fact]
    public async Task StreamHealth_NetworkChanged_TransitionsToInterrupted()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();
        await viewModel.ProcessInputPayloadAsync(MakeInitPayload());
        bridge.EnqueueIncomingPacket(MakeValidPcmPacket());
        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));

        bridge.Diagnostics = new ConnectionDiagnostics(
            FailureHint: "network_changed",
            NormalizedFailureCode: FailureCode.NetworkChanged
        );

        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Interrupted, TimeSpan.FromSeconds(3));

        Assert.Equal(StreamState.Interrupted, viewModel.CurrentStreamState);
        Assert.Contains("network_changed", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task StreamHealth_InterruptedRecovery_TransitionsBackToStreaming()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();
        await viewModel.ProcessInputPayloadAsync(MakeInitPayload());
        bridge.EnqueueIncomingPacket(MakeValidPcmPacket());
        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));

        bridge.Diagnostics = new ConnectionDiagnostics(
            FailureHint: "peer_unreachable",
            NormalizedFailureCode: FailureCode.PeerUnreachable
        );
        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Interrupted, TimeSpan.FromSeconds(3));

        bridge.Diagnostics = new ConnectionDiagnostics();
        bridge.EnqueueIncomingPacket(MakeValidPcmPacket(sequence: 1));
        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));

        Assert.Equal(StreamState.Streaming, viewModel.CurrentStreamState);
        Assert.Contains("復旧", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    // --- Diagnostics display ---

    [Fact]
    public async Task Diagnostics_UsbTetheringPath_DisplayedCorrectly()
    {
        var bridge = new FakeWebRtcBridge
        {
            Diagnostics = new ConnectionDiagnostics(
                PathType: NetworkPathType.UsbTether,
                LocalCandidatesCount: 2,
                SelectedCandidatePairType: "host"
            )
        };
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();

        Assert.Contains("USBテザリング", viewModel.NetworkPathLabel);
        Assert.Contains("2", viewModel.CandidateCountLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task Diagnostics_WifiLanPath_DisplayedCorrectly()
    {
        var bridge = new FakeWebRtcBridge
        {
            Diagnostics = new ConnectionDiagnostics(
                PathType: NetworkPathType.WifiLan,
                LocalCandidatesCount: 3,
                SelectedCandidatePairType: "host"
            )
        };
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();

        Assert.Contains("Wi-Fi / ローカルネットワーク", viewModel.NetworkPathLabel);
        Assert.Contains("3", viewModel.CandidateCountLabel);
        Assert.Contains("host", viewModel.SelectedCandidatePairLabel);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task Diagnostics_FailureHint_MappedToFailureCode()
    {
        var bridge = new FakeWebRtcBridge
        {
            Diagnostics = new ConnectionDiagnostics(
                FailureHint: "usb_tether_detected_but_not_reachable"
            )
        };
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();

        Assert.Contains("usb_tether_detected_but_not_reachable", viewModel.FailureCodeLabel);
        viewModel.Shutdown();
    }

    // --- ApproveVerification without pending answer ---

    [Fact]
    public async Task ApproveVerification_NoPendingAnswer_NoOp()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.ApproveVerificationAndConnectAsync();

        Assert.Contains("適用できる応答データがありません", viewModel.StatusMessage);
        viewModel.Shutdown();
    }

    // --- Computed UI properties ---

    [Fact]
    public void Initial_CanStartSenderAndListener_AreTrue()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        Assert.True(viewModel.CanStartSender);
        Assert.True(viewModel.CanStartListener);
        Assert.False(viewModel.CanProcessPayload);
        Assert.False(viewModel.CanApproveCode);
        Assert.False(viewModel.CanRejectCode);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task AfterStartSender_CannotStartAgain_CanProcessPayload()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();

        Assert.False(viewModel.CanStartSender);
        Assert.False(viewModel.CanStartListener);
        Assert.True(viewModel.CanProcessPayload);
        Assert.True(viewModel.CanStop);
        viewModel.Shutdown();
    }

    [Fact]
    public void AfterStartListener_CanProcessPayload()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();

        Assert.False(viewModel.CanStartSender);
        Assert.False(viewModel.CanStartListener);
        Assert.True(viewModel.CanProcessPayload);
        Assert.True(viewModel.CanStop);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SenderVerifyCode_CanApproveAndReject()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());

        Assert.True(viewModel.CanApproveCode);
        Assert.True(viewModel.CanRejectCode);
        viewModel.Shutdown();
    }

    [Fact]
    public void Initial_RecommendedAction_PromptToChooseRole()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        Assert.Contains("送信側か受信側", viewModel.RecommendedAction);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task Streaming_RecommendedAction_SaysStreaming()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());
        await viewModel.ApproveVerificationAndConnectAsync();

        Assert.Contains("音声共有中", viewModel.RecommendedAction);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task SetupStep_IsPublicAndTracksFlow()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        Assert.Equal(SetupStep.Entry, viewModel.CurrentSetupStep);

        await viewModel.StartSenderAsync();
        Assert.Equal(SetupStep.SenderShowInit, viewModel.CurrentSetupStep);

        await viewModel.ProcessInputPayloadAsync(MakeConfirmPayload());
        Assert.Equal(SetupStep.SenderVerifyCode, viewModel.CurrentSetupStep);

        viewModel.Stop();
        Assert.Equal(SetupStep.Entry, viewModel.CurrentSetupStep);
        viewModel.Shutdown();
    }

    [Fact]
    public void ListenerSetupStep_TracksFlow()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        viewModel.StartListener();
        Assert.Equal(SetupStep.ListenerScanInit, viewModel.CurrentSetupStep);
        viewModel.Shutdown();
    }

    [Fact]
    public async Task AfterStop_CanStartAgain()
    {
        var bridge = new FakeWebRtcBridge();
        var viewModel = CreateViewModel(bridge);

        await viewModel.StartSenderAsync();
        viewModel.Stop();

        Assert.True(viewModel.CanStartSender);
        Assert.True(viewModel.CanStartListener);
        viewModel.Shutdown();
    }

    [Fact]
    public void Shutdown_DisposesOwnedBridges()
    {
        var bridge = new FakeWebRtcBridge();
        var udpBridge = new FakeUdpAudioSenderBridge();
        var viewModel = CreateViewModel(bridge: bridge, udpBridge: udpBridge);

        viewModel.Shutdown();

        Assert.Equal(1, bridge.CloseCalls);
        Assert.Equal(1, udpBridge.CloseCalls);
        Assert.Equal(1, bridge.DisposeCalls);
        Assert.Equal(1, udpBridge.DisposeCalls);
    }

    [Fact]
    public async Task Shutdown_StopsRunningUdpLoopbackSender()
    {
        var udpBridge = new FakeUdpAudioSenderBridge();
        var udpLoopbackSender = new FakeLoopbackAudioSender();
        var connectionCodeFactory = new FakeConnectionCodeSessionFactory();
        var viewModel = CreateViewModel(
            udpBridge: udpBridge,
            connectionCodeSessionFactory: connectionCodeFactory,
            udpLoopbackSender: udpLoopbackSender
        );
        viewModel.SelectTransportMode(TransportMode.UdpOpus);

        await viewModel.StartSenderAsync();
        var sessionId = QrPayloadCodec.DecodeUdpInit(viewModel.CurrentPayload).SessionId;
        connectionCodeFactory.ActiveSession!.CompleteConfirm(
            MakeUdpConfirmPayload(sessionId),
            remoteAddress: "192.168.10.42"
        );

        await WaitUntilAsync(() => viewModel.CurrentStreamState == StreamState.Streaming, TimeSpan.FromSeconds(2));
        Assert.True(udpLoopbackSender.IsRunning);

        viewModel.Shutdown();

        Assert.False(udpLoopbackSender.IsRunning);
        Assert.True(udpLoopbackSender.StopCalls > 0);
        Assert.False(udpBridge.IsStreaming);
    }

    [Fact]
    public async Task InitializeAsync_WhenShutdownBeforeDeferredStartupCompletes_DisposesStartupBridge()
    {
        var startupGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var bridge = new FakeWebRtcBridge
        {
            BackendHealthGate = startupGate
        };
        var viewModel = new MainViewModel(
            bridge,
            new FakeConnectionCodeSessionFactory(),
            initializeImmediately: false
        );

        var initializeTask = viewModel.InitializeAsync();
        await bridge.BackendHealthRequested.Task.WaitAsync(TimeSpan.FromSeconds(2));

        viewModel.Shutdown();
        startupGate.SetResult();
        await initializeTask;

        Assert.Equal(1, bridge.DisposeCalls);
    }

    // --- Helpers ---

    private static async Task WaitUntilAsync(Func<bool> predicate, TimeSpan timeout)
    {
        var started = DateTime.UtcNow;
        while (DateTime.UtcNow - started < timeout)
        {
            if (predicate())
            {
                return;
            }

            await Task.Delay(20);
        }

        Assert.True(predicate());
    }

    private sealed class FakeWebRtcBridge : IWebRtcBridge, IDisposable
    {
        private readonly Queue<byte[]> _incomingPackets = new();
        private readonly object _sync = new();

        public TransportMode Mode => TransportMode.WebRtc;

        public WebRtcOfferResult OfferResult { get; set; } = new(
            Success: true,
            ErrorMessage: string.Empty,
            SessionId: "session-1",
            OfferSdp: "v=0\r\ns=offer\r\n",
            Fingerprint: "sender-fp",
            Diagnostics: new ConnectionDiagnostics()
        );

        public WebRtcAnswerResult AnswerResult { get; set; } = new(
            Success: true,
            ErrorMessage: string.Empty,
            AnswerSdp: "v=0\r\ns=answer\r\n",
            Fingerprint: "receiver-fp",
            Diagnostics: new ConnectionDiagnostics()
        );

        public WebRtcOperationResult ApplyResult { get; set; } = new(
            Success: true,
            ErrorMessage: string.Empty,
            StatusMessage: "ok",
            Diagnostics: new ConnectionDiagnostics()
        );

        public BridgeBackendHealth BackendHealth { get; set; } = new(
            IsReady: true,
            IsDevelopmentStub: false,
            Message: "ネイティブ接続モジュールを利用できます。",
            BlockingFailureCode: null
        );

        public Exception? BackendHealthException { get; set; }

        public TaskCompletionSource BackendHealthRequested { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public TaskCompletionSource? BackendHealthGate { get; set; }

        public ConnectionDiagnostics Diagnostics { get; set; } = new();

        public bool IsNativeBackend => true;

        public int CloseCalls { get; private set; }

        public int DisposeCalls { get; private set; }

        public Task<WebRtcOfferResult> CreateOfferAsync() => Task.FromResult(OfferResult);

        public Task<WebRtcAnswerResult> CreateAnswerAsync(string offerSdp)
        {
            _ = offerSdp;
            return Task.FromResult(AnswerResult);
        }

        public Task<WebRtcOperationResult> ApplyAnswerAsync(string answerSdp)
        {
            _ = answerSdp;
            return Task.FromResult(ApplyResult);
        }

        public bool SendPcmPacket(byte[] packet)
        {
            _ = packet;
            return true;
        }

        public bool TryReceivePcmPacket(out byte[] packet)
        {
            lock (_sync)
            {
                if (_incomingPackets.Count > 0)
                {
                    packet = _incomingPackets.Dequeue();
                    return true;
                }
            }

            packet = [];
            return false;
        }

        public ConnectionDiagnostics GetDiagnostics() => Diagnostics;

        public BridgeBackendHealth GetBackendHealth()
        {
            BackendHealthRequested.TrySetResult();
            BackendHealthGate?.Task.GetAwaiter().GetResult();
            if (BackendHealthException is not null)
            {
                throw BackendHealthException;
            }

            return BackendHealth;
        }

        public void Close()
        {
            CloseCalls++;
        }

        public void EnqueueIncomingPacket(byte[] packet)
        {
            lock (_sync)
            {
                _incomingPackets.Enqueue(packet);
            }
        }

        public void Dispose()
        {
            DisposeCalls++;
        }
    }

    private sealed class FakeUdpAudioSenderBridge : IUdpAudioSenderBridge, IDisposable
    {
        public TransportMode Mode => TransportMode.UdpOpus;

        public bool IsNativeBackend => true;

        public bool IsStreaming { get; private set; }

        public string LastHost { get; private set; } = string.Empty;

        public int LastPort { get; private set; }

        public string LastServiceName { get; private set; } = string.Empty;

        public ConnectionDiagnostics Diagnostics { get; set; } = new(
            PathType: NetworkPathType.WifiLan,
            SelectedCandidatePairType: "udp_opus"
        );

        public BridgeBackendHealth BackendHealth { get; set; } = new(
            IsReady: true,
            IsDevelopmentStub: false,
            Message: "UDP + Opus 送信モジュールを利用できます。",
            BlockingFailureCode: null
        );

        public UdpAudioSenderResult StartResult { get; set; } = new(
            Success: true,
            ErrorMessage: string.Empty,
            StatusMessage: "Android 受信機へ UDP + Opus で送信しています。",
            Diagnostics: new ConnectionDiagnostics(
                PathType: NetworkPathType.WifiLan,
                SelectedCandidatePairType: "udp_opus"
            )
        );

        public Task<UdpAudioSenderResult> StartStreamingAsync(string remoteHost, int remotePort, string remoteServiceName)
        {
            LastHost = remoteHost;
            LastPort = remotePort;
            LastServiceName = remoteServiceName;
            IsStreaming = StartResult.Success;
            return Task.FromResult(StartResult);
        }

        public bool SendPcmFrame(PcmFrame frame)
        {
            _ = frame;
            return IsStreaming;
        }

        public void StopStreaming()
        {
            IsStreaming = false;
        }

        public ConnectionDiagnostics GetDiagnostics() => Diagnostics;

        public BridgeBackendHealth GetBackendHealth() => BackendHealth;

        public int CloseCalls { get; private set; }

        public int DisposeCalls { get; private set; }

        public void Close()
        {
            CloseCalls++;
            StopStreaming();
        }

        public void Dispose()
        {
            DisposeCalls++;
        }
    }

    private sealed class FakeLoopbackAudioSender : ILoopbackAudioSender
    {
        public bool IsRunning { get; private set; }

        public event EventHandler<LoopbackAudioDiagnostics>? DiagnosticsChanged;

        public int StartCalls { get; private set; }

        public int StopCalls { get; private set; }

        public Exception? StartException { get; set; }

        public void Start()
        {
            StartCalls++;
            if (StartException is not null)
            {
                throw StartException;
            }

            IsRunning = true;
        }

        public void Stop()
        {
            if (IsRunning)
            {
                StopCalls++;
            }

            IsRunning = false;
        }

        public void EmitDiagnostics(LoopbackAudioDiagnostics diagnostics)
        {
            DiagnosticsChanged?.Invoke(this, diagnostics);
        }

        public void Dispose()
        {
            Stop();
        }
    }

    private sealed class FakeUdpReceiverDiscoveryService : IUdpReceiverDiscoveryService
    {
        public IReadOnlyList<UdpReceiverEndpoint> Endpoints { get; set; } = [];

        public Exception? DiscoverException { get; set; }

        public Task<IReadOnlyList<UdpReceiverEndpoint>> DiscoverAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (DiscoverException is not null)
            {
                throw DiscoverException;
            }

            return Task.FromResult(Endpoints);
        }
    }

    private sealed class FakeConnectionCodeSessionFactory : IConnectionCodeSessionFactory
    {
        public FakeConnectionCodeSession? ActiveSession { get; private set; }

        public Exception? CreateException { get; set; }

        public IConnectionCodeSession Create(string initPayload, string localAddressHintSource, long expiresAtUnixMs)
        {
            _ = initPayload;
            _ = localAddressHintSource;

            if (CreateException is not null)
            {
                throw CreateException;
            }

            var connectionCode = ConnectionCodeCodec.Encode(new ConnectionCodePayload(
                Host: "192.168.0.10",
                Port: 45678,
                Token: "token-123",
                ExpiresAtUnixMs: expiresAtUnixMs
            ));
            ActiveSession = new FakeConnectionCodeSession(connectionCode, expiresAtUnixMs);
            return ActiveSession;
        }
    }

    private sealed class FakeConnectionCodeSession : IConnectionCodeSession
    {
        private readonly TaskCompletionSource<ConnectionCodeSubmission> _confirmPayloadTcs =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public FakeConnectionCodeSession(string connectionCode, long expiresAtUnixMs)
        {
            ConnectionCode = connectionCode;
            ExpiresAtUnixMs = expiresAtUnixMs;
        }

        public string ConnectionCode { get; }

        public long ExpiresAtUnixMs { get; }

        public Task<ConnectionCodeSubmission> WaitForConfirmPayloadAsync(CancellationToken cancellationToken)
        {
            return _confirmPayloadTcs.Task.WaitAsync(cancellationToken);
        }

        public void CompleteConfirm(string confirmPayload, string remoteAddress = "192.168.0.42")
        {
            _confirmPayloadTcs.TrySetResult(new ConnectionCodeSubmission(confirmPayload, remoteAddress));
        }

        public void Dispose()
        {
            _confirmPayloadTcs.TrySetCanceled();
        }
    }
}

