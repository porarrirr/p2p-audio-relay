using System.Runtime.InteropServices;
using P2PAudio.Windows.App.Logging;
using P2PAudio.Windows.Core.Audio;
using P2PAudio.Windows.Core.Models;
using P2PAudio.Windows.Core.Protocol;

namespace P2PAudio.Windows.App.Services;

public sealed class NativeUdpAudioSenderBridge : IUdpAudioSenderBridge, IDisposable
{
    private readonly nint _handle;
    private bool _disposed;

    public NativeUdpAudioSenderBridge()
    {
        NativeUdpOpusLibraryResolver.EnsureRegistered();
        NativeWebRtcLibraryResolver.EnsureLoaded(NativeUdpOpusLibraryResolver.DllBaseName);
        _handle = NativeUdpOpusNativeMethods.core_udp_opus_create();
        if (_handle == 0)
        {
            throw new InvalidOperationException("Failed to create native UDP Opus sender");
        }

        AppLogger.I("NativeUdpAudioSenderBridge", "bridge_created", "Native UDP Opus sender bridge created");
    }

    public TransportMode Mode => TransportMode.UdpOpus;

    public bool IsNativeBackend => true;

    public bool IsStreaming
    {
        get
        {
            EnsureNotDisposed();
            return NativeUdpOpusNativeMethods.core_udp_opus_is_streaming(_handle) != 0;
        }
    }

    public Task<UdpAudioSenderResult> StartStreamingAsync(
        string remoteHost,
        int remotePort,
        string remoteServiceName,
        UdpOpusApplication application)
    {
        EnsureNotDisposed();
        var native = NativeUdpOpusNativeMethods.core_udp_opus_start_streaming(
            _handle,
            remoteHost ?? string.Empty,
            remotePort,
            (int)application
        );
        try
        {
            var diagnostics = ToDiagnostics(native.diagnostics);
            return Task.FromResult(
                new UdpAudioSenderResult(
                    Success: native.success != 0,
                    ErrorMessage: PtrToString(native.error_message),
                    StatusMessage: string.IsNullOrWhiteSpace(PtrToString(native.status_message))
                        ? $"Android 受信機 {remoteServiceName} に UDP + Opus で送信しています。"
                        : PtrToString(native.status_message),
                    Diagnostics: diagnostics
                )
            );
        }
        finally
        {
            FreeStartResult(native);
        }
    }

    public bool SendPcmFrame(PcmFrame frame)
    {
        EnsureNotDisposed();
        if (frame.BitsPerSample != 16 || frame.PcmBytes.Length == 0)
        {
            return false;
        }

        return NativeUdpOpusNativeMethods.core_udp_opus_send_pcm16(
            _handle,
            frame.PcmBytes,
            frame.PcmBytes.Length,
            frame.SampleRate,
            frame.Channels,
            frame.FrameSamplesPerChannel,
            checked((ulong)frame.TimestampMs)
        ) != 0;
    }

    public void StopStreaming()
    {
        EnsureNotDisposed();
        NativeUdpOpusNativeMethods.core_udp_opus_stop_streaming(_handle);
    }

    public ConnectionDiagnostics GetDiagnostics()
    {
        EnsureNotDisposed();
        var native = NativeUdpOpusNativeMethods.core_udp_opus_get_diagnostics(_handle);
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
            if (NativeUdpOpusNativeMethods.core_udp_opus_has_backend() == 0)
            {
                return new BridgeBackendHealth(
                    IsReady: false,
                    IsDevelopmentStub: false,
                    Message: "UDP + Opus 送信モジュールは読み込めましたが、Opus/UDP 送信サポートが無効です。",
                    BlockingFailureCode: FailureCode.WebRtcNegotiationFailed
                );
            }
        }
        catch (EntryPointNotFoundException)
        {
        }

        return new BridgeBackendHealth(
            IsReady: true,
            IsDevelopmentStub: false,
            Message: "UDP + Opus 送信モジュールを利用できます。",
            BlockingFailureCode: null
        );
    }

    public void Close()
    {
        if (_disposed)
        {
            return;
        }

        NativeUdpOpusNativeMethods.core_udp_opus_stop_streaming(_handle);
    }

    public void Dispose()
    {
        Dispose(disposing: true);
        GC.SuppressFinalize(this);
    }

    ~NativeUdpAudioSenderBridge()
    {
        Dispose(disposing: false);
    }

    private void EnsureNotDisposed()
    {
        if (_disposed)
        {
            throw new ObjectDisposedException(nameof(NativeUdpAudioSenderBridge));
        }
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
                AppLogger.I("NativeUdpAudioSenderBridge", "bridge_dispose", "Disposing native UDP Opus sender bridge");
            }

            NativeUdpOpusNativeMethods.core_udp_opus_stop_streaming(_handle);
            NativeUdpOpusNativeMethods.core_udp_opus_destroy(_handle);
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

    private static ConnectionDiagnostics ToDiagnostics(NativeUdpOpusNativeMethods.core_udp_opus_diagnostics native)
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
        if (value == 0)
        {
            return string.Empty;
        }

        return Marshal.PtrToStringAnsi(value) ?? string.Empty;
    }

    private static void FreeDiagnostics(NativeUdpOpusNativeMethods.core_udp_opus_diagnostics diagnostics)
    {
        NativeUdpOpusNativeMethods.core_udp_opus_free_string(diagnostics.path_type);
        NativeUdpOpusNativeMethods.core_udp_opus_free_string(diagnostics.selected_candidate_pair_type);
        NativeUdpOpusNativeMethods.core_udp_opus_free_string(diagnostics.failure_hint);
    }

    private static void FreeStartResult(NativeUdpOpusNativeMethods.core_udp_opus_start_result result)
    {
        NativeUdpOpusNativeMethods.core_udp_opus_free_string(result.error_message);
        NativeUdpOpusNativeMethods.core_udp_opus_free_string(result.status_message);
        FreeDiagnostics(result.diagnostics);
    }
}

internal static class NativeUdpOpusNativeMethods
{
    private const string DllName = NativeUdpOpusLibraryResolver.DllBaseName;

    static NativeUdpOpusNativeMethods()
    {
        NativeUdpOpusLibraryResolver.EnsureRegistered();
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct core_udp_opus_diagnostics
    {
        public nint path_type;
        public int local_candidates_count;
        public nint selected_candidate_pair_type;
        public nint failure_hint;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct core_udp_opus_start_result
    {
        public int success;
        public nint error_message;
        public nint status_message;
        public core_udp_opus_diagnostics diagnostics;
    }

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern nint core_udp_opus_create();

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void core_udp_opus_destroy(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    internal static extern core_udp_opus_start_result core_udp_opus_start_streaming(
        nint handle,
        string remoteHost,
        int remotePort,
        int application);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int core_udp_opus_send_pcm16(
        nint handle,
        byte[] pcmBytes,
        int pcmByteCount,
        int sampleRate,
        int channels,
        int frameSamplesPerChannel,
        ulong timestampMs);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void core_udp_opus_stop_streaming(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int core_udp_opus_is_streaming(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern core_udp_opus_diagnostics core_udp_opus_get_diagnostics(nint handle);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int core_udp_opus_has_backend();

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void core_udp_opus_free_string(nint value);
}
