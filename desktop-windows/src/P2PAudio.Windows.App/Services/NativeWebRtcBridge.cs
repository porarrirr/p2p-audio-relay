using System.Runtime.InteropServices;
using P2PAudio.Windows.App.Logging;
using P2PAudio.Windows.Core.Models;
using P2PAudio.Windows.Core.Protocol;

namespace P2PAudio.Windows.App.Services;

public sealed class NativeWebRtcBridge : IWebRtcBridge, IDisposable
{
    private const int MaxReceivePacketBytes = 65_536;
    private const long SendFailureLogIntervalMs = 1_000;

    private readonly nint _handle;
    private readonly byte[] _receiveBuffer = new byte[MaxReceivePacketBytes];
    private bool _disposed;
    private long _lastSendFailureLogAtMs;

    public NativeWebRtcBridge()
    {
        NativeWebRtcLibraryResolver.EnsureRegistered();
        NativeWebRtcLibraryResolver.EnsureLoaded("p2paudio_core_webrtc");
        _handle = NativeMethods.core_webrtc_create();
        if (_handle == 0)
        {
            throw new InvalidOperationException("Failed to create native WebRTC controller");
        }

        AppLogger.I("NativeWebRtcBridge", "bridge_created", "Native WebRTC bridge created");
    }

    public bool IsNativeBackend => true;

    public TransportMode Mode => TransportMode.WebRtc;

    public Task<WebRtcOfferResult> CreateOfferAsync()
    {
        EnsureNotDisposed();
        var native = NativeMethods.core_webrtc_create_offer(_handle);
        try
        {
            var diagnostics = ToDiagnostics(native.diagnostics);
            var offerSdp = PtrToString(native.offer_sdp);
            var success = native.success != 0 &&
                          !string.IsNullOrWhiteSpace(offerSdp) &&
                          diagnostics.NormalizedFailureCode is null;
            return Task.FromResult(
                new WebRtcOfferResult(
                    Success: success,
                    ErrorMessage: PtrToString(native.error_message),
                    SessionId: PtrToString(native.session_id),
                    OfferSdp: offerSdp,
                    Fingerprint: PtrToString(native.fingerprint),
                    Diagnostics: diagnostics
                )
            );
        }
        finally
        {
            FreeOffer(native);
        }
    }

    public Task<WebRtcAnswerResult> CreateAnswerAsync(string offerSdp)
    {
        EnsureNotDisposed();
        var native = NativeMethods.core_webrtc_create_answer(_handle, offerSdp ?? string.Empty);
        try
        {
            var diagnostics = ToDiagnostics(native.diagnostics);
            var answerSdp = PtrToString(native.answer_sdp);
            var success = native.success != 0 &&
                          !string.IsNullOrWhiteSpace(answerSdp) &&
                          diagnostics.NormalizedFailureCode is null;
            return Task.FromResult(
                new WebRtcAnswerResult(
                    Success: success,
                    ErrorMessage: PtrToString(native.error_message),
                    AnswerSdp: answerSdp,
                    Fingerprint: PtrToString(native.fingerprint),
                    Diagnostics: diagnostics
                )
            );
        }
        finally
        {
            FreeAnswer(native);
        }
    }

    public Task<WebRtcOperationResult> ApplyAnswerAsync(string answerSdp)
    {
        EnsureNotDisposed();
        var native = NativeMethods.core_webrtc_apply_answer(_handle, answerSdp ?? string.Empty);
        try
        {
            var diagnostics = ToDiagnostics(native.diagnostics);
            return Task.FromResult(
                new WebRtcOperationResult(
                    Success: native.success != 0,
                    ErrorMessage: PtrToString(native.error_message),
                    StatusMessage: native.success != 0 ? "応答データを適用しました。" : "応答データの適用に失敗しました。",
                    Diagnostics: diagnostics
                )
            );
        }
        finally
        {
            FreeApply(native);
        }
    }

    public bool SendPcmPacket(byte[] packet)
    {
        EnsureNotDisposed();
        if (packet.Length == 0)
        {
            AppLogger.W("NativeWebRtcBridge", "send_empty_packet", "Attempted to send an empty PCM packet");
            return false;
        }

        var sent = NativeMethods.core_webrtc_send_pcm_frame(_handle, packet, (nuint)packet.Length) != 0;
        if (!sent)
        {
            var now = Environment.TickCount64;
            if (now - _lastSendFailureLogAtMs >= SendFailureLogIntervalMs)
            {
                _lastSendFailureLogAtMs = now;
                var diagnostics = GetDiagnostics();
                AppLogger.W(
                    "NativeWebRtcBridge",
                    "send_pcm_failed",
                    "Native bridge rejected a PCM packet",
                    new Dictionary<string, object?>
                    {
                        ["packetBytes"] = packet.Length,
                        ["failureHint"] = diagnostics.FailureHint,
                        ["pathType"] = diagnostics.PathType.ToString(),
                        ["selectedCandidatePairType"] = diagnostics.SelectedCandidatePairType
                    }
                );
            }
        }

        return sent;
    }

    public bool TryReceivePcmPacket(out byte[] packet)
    {
        EnsureNotDisposed();
        packet = [];

        var result = NativeMethods.core_webrtc_pop_pcm_frame(
            _handle,
            _receiveBuffer,
            (nuint)_receiveBuffer.Length,
            out var packetSize
        );

        if (result <= 0 || packetSize == 0)
        {
            return false;
        }

        var count = checked((int)packetSize);
        packet = new byte[count];
        Buffer.BlockCopy(_receiveBuffer, 0, packet, 0, count);
        return true;
    }

    public ConnectionDiagnostics GetDiagnostics()
    {
        EnsureNotDisposed();
        var native = NativeMethods.core_webrtc_get_diagnostics(_handle);
        try
        {
            return ToDiagnostics(native);
        }
        finally
        {
            FreeDiagnostics(native);
        }
    }

    public BridgeBackendHealth GetBackendHealth()
    {
        try
        {
            if (NativeMethods.core_webrtc_has_libdatachannel() == 0)
            {
                return new BridgeBackendHealth(
                    IsReady: false,
                    IsDevelopmentStub: false,
                    Message: "ネイティブ接続モジュールは読み込めましたが、p2paudio_core_webrtc.dll の libdatachannel サポートが無効です。",
                    BlockingFailureCode: FailureCode.WebRtcNegotiationFailed
                );
            }
        }
        catch (EntryPointNotFoundException)
        {
            // Older native DLLs may not export backend capability yet.
        }

        return new BridgeBackendHealth(
            IsReady: true,
            IsDevelopmentStub: false,
            Message: "ネイティブ接続モジュールを利用できます。",
            BlockingFailureCode: null
        );
    }

    public void Close()
    {
        if (_disposed) return;
        AppLogger.I("NativeWebRtcBridge", "bridge_close", "Closing native WebRTC bridge");
        NativeMethods.core_webrtc_close(_handle);
    }

    private void EnsureNotDisposed()
    {
        if (_disposed)
        {
            throw new ObjectDisposedException(nameof(NativeWebRtcBridge));
        }
    }

    private static ConnectionDiagnostics ToDiagnostics(NativeMethods.core_webrtc_diagnostics native)
    {
        var pathRaw = PtrToString(native.path_type);
        var failureHint = PtrToString(native.failure_hint);
        var path = pathRaw switch
        {
            "wifi_lan" => NetworkPathType.WifiLan,
            "usb_tether" => NetworkPathType.UsbTether,
            _ => NetworkPathType.Unknown
        };
        return new ConnectionDiagnostics(
            PathType: path,
            LocalCandidatesCount: native.local_candidates_count,
            SelectedCandidatePairType: PtrToString(native.selected_candidate_pair_type),
            FailureHint: failureHint,
            NormalizedFailureCode: FailureCodeMapper.FromFailureHint(failureHint)
        );
    }

    private static string PtrToString(nint value)
    {
        if (value == 0) return string.Empty;
        return Marshal.PtrToStringAnsi(value) ?? string.Empty;
    }

    private static void FreeDiagnostics(NativeMethods.core_webrtc_diagnostics diagnostics)
    {
        NativeMethods.core_webrtc_free_string(diagnostics.path_type);
        NativeMethods.core_webrtc_free_string(diagnostics.selected_candidate_pair_type);
        NativeMethods.core_webrtc_free_string(diagnostics.failure_hint);
    }

    private static void FreeOffer(NativeMethods.core_webrtc_offer_result result)
    {
        NativeMethods.core_webrtc_free_string(result.error_message);
        NativeMethods.core_webrtc_free_string(result.session_id);
        NativeMethods.core_webrtc_free_string(result.offer_sdp);
        NativeMethods.core_webrtc_free_string(result.fingerprint);
        FreeDiagnostics(result.diagnostics);
    }

    private static void FreeAnswer(NativeMethods.core_webrtc_answer_result result)
    {
        NativeMethods.core_webrtc_free_string(result.error_message);
        NativeMethods.core_webrtc_free_string(result.answer_sdp);
        NativeMethods.core_webrtc_free_string(result.fingerprint);
        FreeDiagnostics(result.diagnostics);
    }

    private static void FreeApply(NativeMethods.core_webrtc_apply_result result)
    {
        NativeMethods.core_webrtc_free_string(result.error_message);
        FreeDiagnostics(result.diagnostics);
    }

    public void Dispose()
    {
        Dispose(disposing: true);
        GC.SuppressFinalize(this);
    }

    ~NativeWebRtcBridge()
    {
        Dispose(disposing: false);
    }

    private void Dispose(bool disposing)
    {
        if (_disposed)
        {
            return;
        }

        try
        {
            if (disposing)
            {
                AppLogger.I("NativeWebRtcBridge", "bridge_dispose", "Disposing native WebRTC bridge");
            }

            NativeMethods.core_webrtc_close(_handle);
            NativeMethods.core_webrtc_destroy(_handle);
        }
        catch when (!disposing)
        {
            // Finalizer-thread teardown must never terminate the process.
        }
        finally
        {
            _disposed = true;
        }
    }
}

internal static class NativeMethods
{
    private const string DllName = "p2paudio_core_webrtc";

    static NativeMethods()
    {
        NativeWebRtcLibraryResolver.EnsureRegistered();
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct core_webrtc_diagnostics
    {
        public nint path_type;
        public int local_candidates_count;
        public nint selected_candidate_pair_type;
        public nint failure_hint;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct core_webrtc_offer_result
    {
        public int success;
        public nint error_message;
        public nint session_id;
        public nint offer_sdp;
        public nint fingerprint;
        public core_webrtc_diagnostics diagnostics;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct core_webrtc_answer_result
    {
        public int success;
        public nint error_message;
        public nint answer_sdp;
        public nint fingerprint;
        public core_webrtc_diagnostics diagnostics;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct core_webrtc_apply_result
    {
        public int success;
        public nint error_message;
        public core_webrtc_diagnostics diagnostics;
    }

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern nint core_webrtc_create();

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void core_webrtc_destroy(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern core_webrtc_offer_result core_webrtc_create_offer(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    internal static extern core_webrtc_answer_result core_webrtc_create_answer(nint handle, string offerSdp);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    internal static extern core_webrtc_apply_result core_webrtc_apply_answer(nint handle, string answerSdp);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int core_webrtc_send_pcm_frame(nint handle, byte[] data, nuint size);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int core_webrtc_pop_pcm_frame(nint handle, [Out] byte[]? outBuffer, nuint capacity, out nuint outSize);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern core_webrtc_diagnostics core_webrtc_get_diagnostics(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int core_webrtc_has_libdatachannel();

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void core_webrtc_close(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void core_webrtc_free_string(nint value);
}
