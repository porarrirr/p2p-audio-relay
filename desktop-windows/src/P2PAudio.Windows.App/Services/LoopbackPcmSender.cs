using System.Collections.Concurrent;
using System.Diagnostics;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using P2PAudio.Windows.App.Logging;
using P2PAudio.Windows.Core.Audio;

namespace P2PAudio.Windows.App.Services;

public sealed class LoopbackPcmSender : ILoopbackAudioSender
{
    private const long StatsLogIntervalMs = 5_000;
    private const long DiagnosticsPublishIntervalMs = 250;
    private const int DefaultFramesPerSecond = 50;
    private const int ResampleBufferBytes = 16_384;
    private const int MaxPendingSendFrames = 12;
    private const int SendLoopStopTimeoutMs = 1_000;

    private readonly Func<PcmFrame, bool> _sendFrame;
    private readonly object _sync = new();
    private readonly LoopbackCaptureOptions _options;
    private readonly AudioFrameTimingClock _frameTimingClock = new();
    private readonly List<byte> _pendingPcm16 = [];
    private WasapiCapture? _capture;
    private BufferedWaveProvider? _resampleInputBuffer;
    private MediaFoundationResampler? _resampler;
    private BlockingCollection<PendingFrame>? _pendingSendQueue;
    private CancellationTokenSource? _sendLoopCts;
    private Task? _sendLoopTask;
    private int _sampleRate;
    private int _channels;
    private int _frameSamples;
    private int _sequence;
    private bool _isRunning;
    private long _sentFrames;
    private long _sendFailures;
    private long _pendingSendDrops;
    private long _lastStatsLogAtMs = Environment.TickCount64;
    private long _lastSendFailureLogAtMs;
    private long _lastQueueDropLogAtMs;
    private long _lastDiagnosticsPublishedAtMs;
    private bool _frameTimingInitialized;

    public LoopbackPcmSender(Func<byte[], bool> sendPacket)
        : this(frame => sendPacket(PcmPacketCodec.Encode(frame)))
    {
    }

    public LoopbackPcmSender(Func<PcmFrame, bool> sendFrame, LoopbackCaptureOptions? options = null)
    {
        _sendFrame = sendFrame ?? throw new ArgumentNullException(nameof(sendFrame));
        _options = options ?? new LoopbackCaptureOptions();
    }

    public event EventHandler<LoopbackAudioDiagnostics>? DiagnosticsChanged;

    public bool IsRunning
    {
        get
        {
            lock (_sync)
            {
                return _isRunning;
            }
        }
    }

    public void Start()
    {
        WasapiCapture? capture = null;
        LoopbackAudioDiagnostics diagnostics;
        SendLoopHandle sendLoopHandle = new(null, null, null);
        int inputSampleRate = 0;
        int outputSampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        string encoding = string.Empty;

        try
        {
            lock (_sync)
            {
                if (_isRunning)
                {
                    return;
                }

                capture = new LowLatencyWasapiLoopbackCapture(_options.AudioBufferMilliseconds);
                _capture = capture;
                _sampleRate = ResolveOutputSampleRate(capture.WaveFormat.SampleRate);
                _channels = PcmCaptureNormalizer.GetOutputChannels(capture.WaveFormat.Channels);
                _frameSamples = _options.ResolveFrameSamples(_sampleRate);
                _sequence = 0;
                ResetTelemetryUnsafe();
                _pendingPcm16.Clear();
                ConfigureResamplerUnsafe(capture.WaveFormat.SampleRate, _channels);
                _frameTimingInitialized = false;

                _pendingSendQueue = new BlockingCollection<PendingFrame>(
                    new ConcurrentQueue<PendingFrame>(),
                    MaxPendingSendFrames
                );
                _sendLoopCts = new CancellationTokenSource();
                _sendLoopTask = Task.Run(() => SendLoopAsync(_pendingSendQueue, _sendLoopCts.Token));

                capture.DataAvailable += OnDataAvailable;
                capture.RecordingStopped += OnRecordingStopped;
                capture.StartRecording();

                _isRunning = true;
                diagnostics = BuildDiagnosticsUnsafe();
                inputSampleRate = capture.WaveFormat.SampleRate;
                outputSampleRate = _sampleRate;
                channels = _channels;
                bitsPerSample = capture.WaveFormat.BitsPerSample;
                encoding = capture.WaveFormat.Encoding.ToString();
            }
        }
        catch
        {
            if (capture is not null)
            {
                capture.DataAvailable -= OnDataAvailable;
                capture.RecordingStopped -= OnRecordingStopped;
                capture.Dispose();
            }

            lock (_sync)
            {
                _capture = null;
                DisposeResamplerUnsafe();
                sendLoopHandle = DetachSendLoopUnsafe();
                ResetTelemetryUnsafe();
                ResetAudioFormatUnsafe();
            }

            StopSendLoop(sendLoopHandle);
            throw;
        }

        PublishDiagnostics(diagnostics, force: true);
        AppLogger.I(
            "LoopbackSender",
            "capture_started",
            "WASAPI loopback capture started",
            new Dictionary<string, object?>
            {
                ["inputSampleRate"] = inputSampleRate,
                ["outputSampleRate"] = outputSampleRate,
                ["channels"] = channels,
                ["bitsPerSample"] = bitsPerSample,
                ["encoding"] = encoding,
                ["framesPerSecond"] = _options.FramesPerSecond,
                ["audioBufferMilliseconds"] = _options.AudioBufferMilliseconds
            }
        );
    }

    public void Stop()
    {
        WasapiCapture? capture;
        SendLoopHandle sendLoopHandle;
        LoopbackAudioDiagnostics diagnostics;
        long sentFrames;
        long sendFailures;
        long pendingSendDrops;

        lock (_sync)
        {
            if (!_isRunning && _capture is null && _pendingSendQueue is null)
            {
                return;
            }

            capture = _capture;
            if (capture is not null)
            {
                capture.DataAvailable -= OnDataAvailable;
                capture.RecordingStopped -= OnRecordingStopped;
            }

            _capture = null;
            _isRunning = false;
            _pendingPcm16.Clear();
            DisposeResamplerUnsafe();
            sendLoopHandle = DetachSendLoopUnsafe();

            sentFrames = _sentFrames;
            sendFailures = _sendFailures;
            pendingSendDrops = _pendingSendDrops;

            ResetTelemetryUnsafe();
            ResetAudioFormatUnsafe();
            diagnostics = BuildDiagnosticsUnsafe();
        }

        if (capture is not null)
        {
            capture.StopRecording();
            capture.Dispose();
        }

        StopSendLoop(sendLoopHandle);
        PublishDiagnostics(diagnostics, force: true);

        AppLogger.I(
            "LoopbackSender",
            "capture_stopped",
            "WASAPI loopback capture stopped",
            new Dictionary<string, object?>
            {
                ["sentFrames"] = sentFrames,
                ["sendFailures"] = sendFailures,
                ["pendingSendDrops"] = pendingSendDrops
            }
        );
    }

    public void Dispose()
    {
        Stop();
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        List<PendingFrame> framesToQueue;
        lock (_sync)
        {
            if (!_isRunning || _capture is null)
            {
                return;
            }

            AppendAsPcm16(e.Buffer, e.BytesRecorded, _capture.WaveFormat);
            framesToQueue = DrainFramesUnsafe();
        }

        EnqueueFrames(framesToQueue);
        LogStatsIfNeeded();
        PublishDiagnosticsIfNeeded();
    }

    private void OnRecordingStopped(object? sender, StoppedEventArgs e)
    {
        SendLoopHandle sendLoopHandle;
        LoopbackAudioDiagnostics diagnostics;

        lock (_sync)
        {
            _isRunning = false;
            _capture = null;
            _pendingPcm16.Clear();
            DisposeResamplerUnsafe();
            sendLoopHandle = DetachSendLoopUnsafe();
            ResetTelemetryUnsafe();
            ResetAudioFormatUnsafe();
            diagnostics = BuildDiagnosticsUnsafe();
        }

        StopSendLoop(sendLoopHandle);
        PublishDiagnostics(diagnostics, force: true);

        if (e.Exception is not null)
        {
            AppLogger.E(
                "LoopbackSender",
                "capture_stopped_with_error",
                "Loopback capture stopped with an error",
                exception: e.Exception
            );
            return;
        }

        AppLogger.I("LoopbackSender", "capture_stopped_event", "Loopback capture stopped");
    }

    private void AppendAsPcm16(byte[] input, int bytesRecorded, WaveFormat format)
    {
        PcmNormalizationResult? normalized = null;
        if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32)
        {
            normalized = PcmCaptureNormalizer.NormalizeFloat32(input.AsSpan(0, bytesRecorded), format.Channels);
        }
        else if (format.Encoding == WaveFormatEncoding.Pcm && format.BitsPerSample == 16)
        {
            normalized = PcmCaptureNormalizer.NormalizePcm16(input.AsSpan(0, bytesRecorded), format.Channels);
        }

        if (normalized is null || normalized.PcmBytes.Length == 0)
        {
            return;
        }

        _channels = normalized.Channels;
        _sampleRate = ResolveOutputSampleRate(format.SampleRate);
        _frameSamples = _options.ResolveFrameSamples(_sampleRate);

        if (_resampler is null)
        {
            _pendingPcm16.AddRange(normalized.PcmBytes);
            return;
        }

        _resampleInputBuffer!.AddSamples(normalized.PcmBytes, 0, normalized.PcmBytes.Length);
        DrainResampledPcmUnsafe();
    }

    private void DrainResampledPcmUnsafe()
    {
        var resampledBuffer = new byte[ResampleBufferBytes];
        while (true)
        {
            var bytesRead = _resampler!.Read(resampledBuffer, 0, resampledBuffer.Length);
            if (bytesRead <= 0)
            {
                return;
            }

            var chunk = new byte[bytesRead];
            Buffer.BlockCopy(resampledBuffer, 0, chunk, 0, bytesRead);
            _pendingPcm16.AddRange(chunk);
            if (bytesRead < resampledBuffer.Length)
            {
                return;
            }
        }
    }

    private void ConfigureResamplerUnsafe(int inputSampleRate, int channels)
    {
        DisposeResamplerUnsafe();

        var targetSampleRate = _options.TargetSampleRate;
        if (targetSampleRate is null || targetSampleRate == inputSampleRate)
        {
            return;
        }

        var inputFormat = new WaveFormat(inputSampleRate, 16, channels);
        _resampleInputBuffer = new BufferedWaveProvider(inputFormat)
        {
            BufferDuration = TimeSpan.FromSeconds(2),
            DiscardOnBufferOverflow = true,
            ReadFully = false
        };
        _resampler = new MediaFoundationResampler(
            _resampleInputBuffer,
            new WaveFormat(targetSampleRate.Value, 16, channels))
        {
            ResamplerQuality = 60
        };
    }

    private void DisposeResamplerUnsafe()
    {
        _resampler?.Dispose();
        _resampler = null;
        _resampleInputBuffer = null;
    }

    private int ResolveOutputSampleRate(int inputSampleRate)
    {
        return _options.TargetSampleRate ?? inputSampleRate;
    }

    private void EnsureFrameTimingClockInitializedUnsafe()
    {
        if (_frameTimingInitialized)
        {
            return;
        }

        _frameTimingClock.Reset(
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Environment.TickCount64,
            Stopwatch.GetTimestamp());
        _frameTimingInitialized = true;
    }

    private List<PendingFrame> DrainFramesUnsafe()
    {
        var frames = new List<PendingFrame>();
        var frameBytes = _frameSamples * _channels * sizeof(short);
        while (_pendingPcm16.Count >= frameBytes)
        {
            var pcm = _pendingPcm16.GetRange(0, frameBytes).ToArray();
            _pendingPcm16.RemoveRange(0, frameBytes);
            EnsureFrameTimingClockInitializedUnsafe();
            var timing = _frameTimingClock.Next(_sampleRate, _frameSamples);

            frames.Add(new PendingFrame(
                new PcmFrame(
                    Sequence: _sequence++,
                    TimestampMs: timing.TimestampMs,
                    SampleRate: _sampleRate,
                    Channels: _channels,
                    BitsPerSample: 16,
                    FrameSamplesPerChannel: _frameSamples,
                    PcmBytes: pcm
                ),
                timing.DueAtTickMs,
                timing.DueAtTimestampTicks
            ));
        }

        return frames;
    }

    private void EnqueueFrames(IEnumerable<PendingFrame> frames)
    {
        foreach (var pendingFrame in frames)
        {
            if (!TryEnqueueFrame(pendingFrame))
            {
                return;
            }
        }
    }

    private bool TryEnqueueFrame(PendingFrame pendingFrame)
    {
        BlockingCollection<PendingFrame>? queue;
        lock (_sync)
        {
            if (!_isRunning)
            {
                return false;
            }

            queue = _pendingSendQueue;
        }

        if (queue is null || queue.IsAddingCompleted)
        {
            return false;
        }

        while (!queue.TryAdd(pendingFrame))
        {
            if (!queue.TryTake(out var droppedFrame))
            {
                return false;
            }

            long pendingSendDrops;
            var shouldLog = false;
            lock (_sync)
            {
                _pendingSendDrops++;
                pendingSendDrops = _pendingSendDrops;
                var now = Environment.TickCount64;
                if (now - _lastQueueDropLogAtMs >= 1_000)
                {
                    _lastQueueDropLogAtMs = now;
                    shouldLog = true;
                }
            }

            if (shouldLog)
            {
                AppLogger.W(
                    "LoopbackSender",
                    "sender_queue_overflow",
                    "Dropped the oldest pending frame to keep sender latency bounded",
                    new Dictionary<string, object?>
                    {
                        ["droppedSequence"] = droppedFrame.Frame.Sequence,
                        ["pendingSendDrops"] = pendingSendDrops
                    }
                );
            }
        }

        return true;
    }

    private async Task SendLoopAsync(
        BlockingCollection<PendingFrame> pendingSendQueue,
        CancellationToken cancellationToken)
    {
        try
        {
            foreach (var pendingFrame in pendingSendQueue.GetConsumingEnumerable(cancellationToken))
            {
                await DelayUntilDueAsync(pendingFrame.DueAtTimestampTicks, cancellationToken);

                var success = false;
                string? exceptionMessage = null;
                try
                {
                    success = _sendFrame(pendingFrame.Frame);
                }
                catch (Exception ex)
                {
                    exceptionMessage = ex.Message;
                }

                long sendFailures;
                var shouldLogFailure = false;
                lock (_sync)
                {
                    if (success)
                    {
                        _sentFrames++;
                    }
                    else
                    {
                        _sendFailures++;
                        var now = Environment.TickCount64;
                        if (now - _lastSendFailureLogAtMs >= 1_000)
                        {
                            _lastSendFailureLogAtMs = now;
                            shouldLogFailure = true;
                        }
                    }

                    sendFailures = _sendFailures;
                }

                if (!success && shouldLogFailure)
                {
                    AppLogger.W(
                        "LoopbackSender",
                        "pcm_send_failed",
                        "Failed to send loopback PCM frame",
                        new Dictionary<string, object?>
                        {
                            ["sequence"] = pendingFrame.Frame.Sequence,
                            ["sampleRate"] = pendingFrame.Frame.SampleRate,
                            ["channels"] = pendingFrame.Frame.Channels,
                            ["frameSamplesPerChannel"] = pendingFrame.Frame.FrameSamplesPerChannel,
                            ["sendFailures"] = sendFailures,
                            ["exception"] = exceptionMessage
                        }
                    );
                }

                LogStatsIfNeeded();
                PublishDiagnosticsIfNeeded();
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }

    private static async Task DelayUntilDueAsync(long dueAtTimestampTicks, CancellationToken cancellationToken)
    {
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var remainingTicks = dueAtTimestampTicks - Stopwatch.GetTimestamp();
            if (remainingTicks <= 0)
            {
                return;
            }

            var remainingMs = remainingTicks * 1000d / Stopwatch.Frequency;
            if (remainingMs >= 3)
            {
                var coarseDelayMs = Math.Max(1, (int)Math.Floor(remainingMs - 1));
                await Task.Delay(coarseDelayMs, cancellationToken);
                continue;
            }

            if (remainingMs >= 1)
            {
                Thread.Sleep(0);
                continue;
            }

            Thread.SpinWait(256);
        }
    }

    private void LogStatsIfNeeded()
    {
        LoopbackAudioDiagnostics diagnostics;
        lock (_sync)
        {
            var now = Environment.TickCount64;
            if (now - _lastStatsLogAtMs < StatsLogIntervalMs)
            {
                return;
            }

            _lastStatsLogAtMs = now;
            diagnostics = BuildDiagnosticsUnsafe();
        }

        AppLogger.D(
            "LoopbackSender",
            "sender_stats",
            "Loopback sender stats",
            new Dictionary<string, object?>
            {
                ["sentFrames"] = diagnostics.SentFrames,
                ["sendFailures"] = diagnostics.SendFailures,
                ["pendingPcmBytes"] = diagnostics.PendingPcmBytes,
                ["pendingSendFrames"] = diagnostics.PendingSendFrames,
                ["pendingSendDrops"] = diagnostics.PendingSendDrops,
                ["sampleRate"] = diagnostics.SampleRate,
                ["channels"] = diagnostics.Channels,
                ["frameDurationMs"] = diagnostics.FrameDurationMs
            }
        );
    }

    private void PublishDiagnosticsIfNeeded(bool force = false)
    {
        LoopbackAudioDiagnostics diagnostics;
        EventHandler<LoopbackAudioDiagnostics>? handler;

        lock (_sync)
        {
            if (!force)
            {
                var now = Environment.TickCount64;
                if (now - _lastDiagnosticsPublishedAtMs < DiagnosticsPublishIntervalMs)
                {
                    return;
                }

                _lastDiagnosticsPublishedAtMs = now;
            }
            else
            {
                _lastDiagnosticsPublishedAtMs = Environment.TickCount64;
            }

            diagnostics = BuildDiagnosticsUnsafe();
            handler = DiagnosticsChanged;
        }

        handler?.Invoke(this, diagnostics);
    }

    private void PublishDiagnostics(LoopbackAudioDiagnostics diagnostics, bool force)
    {
        EventHandler<LoopbackAudioDiagnostics>? handler;
        lock (_sync)
        {
            if (force)
            {
                _lastDiagnosticsPublishedAtMs = Environment.TickCount64;
            }

            handler = DiagnosticsChanged;
        }

        handler?.Invoke(this, diagnostics);
    }

    private LoopbackAudioDiagnostics BuildDiagnosticsUnsafe()
    {
        var frameDurationMs = _sampleRate <= 0 || _frameSamples <= 0
            ? 0
            : Math.Max(1, (int)Math.Round((_frameSamples * 1000d) / _sampleRate));

        return new LoopbackAudioDiagnostics(
            IsActive: _isRunning,
            SampleRate: _sampleRate,
            Channels: _channels,
            BitsPerSample: _sampleRate > 0 ? 16 : 0,
            FrameSamplesPerChannel: _frameSamples,
            FrameDurationMs: frameDurationMs,
            FramesPerSecond: _options.FramesPerSecond,
            PendingPcmBytes: _pendingPcm16.Count,
            PendingSendFrames: _pendingSendQueue?.Count ?? 0,
            SentFrames: _sentFrames,
            SendFailures: _sendFailures,
            PendingSendDrops: _pendingSendDrops,
            PacingEnabled: true
        );
    }

    private void ResetTelemetryUnsafe()
    {
        _sentFrames = 0;
        _sendFailures = 0;
        _pendingSendDrops = 0;
        _lastStatsLogAtMs = Environment.TickCount64;
        _lastSendFailureLogAtMs = 0;
        _lastQueueDropLogAtMs = 0;
        _lastDiagnosticsPublishedAtMs = 0;
    }

    private void ResetAudioFormatUnsafe()
    {
        _sampleRate = 0;
        _channels = 0;
        _frameSamples = 0;
        _frameTimingInitialized = false;
    }

    private SendLoopHandle DetachSendLoopUnsafe()
    {
        var handle = new SendLoopHandle(_pendingSendQueue, _sendLoopCts, _sendLoopTask);
        _pendingSendQueue = null;
        _sendLoopCts = null;
        _sendLoopTask = null;
        return handle;
    }

    private static void StopSendLoop(SendLoopHandle handle)
    {
        handle.Queue?.CompleteAdding();
        handle.Cancellation?.Cancel();

        try
        {
            handle.Task?.Wait(SendLoopStopTimeoutMs);
        }
        catch (AggregateException)
        {
        }

        handle.Queue?.Dispose();
        handle.Cancellation?.Dispose();
    }

    private sealed record PendingFrame(PcmFrame Frame, long DueAtTickMs, long DueAtTimestampTicks);

    private sealed record SendLoopHandle(
        BlockingCollection<PendingFrame>? Queue,
        CancellationTokenSource? Cancellation,
        Task? Task
    );
}

public sealed class LoopbackCaptureOptions
{
    private const int DefaultFrameDurationMs = 20;
    private const int DefaultAudioBufferMilliseconds = 20;

    public LoopbackCaptureOptions(
        int? targetSampleRate = null,
        int frameDurationMs = DefaultFrameDurationMs,
        int audioBufferMilliseconds = DefaultAudioBufferMilliseconds,
        UdpOpusApplication application = UdpOpusApplication.RestrictedLowDelay)
    {
        if (targetSampleRate is <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(targetSampleRate));
        }

        if (frameDurationMs <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(frameDurationMs));
        }

        if (audioBufferMilliseconds <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(audioBufferMilliseconds));
        }

        TargetSampleRate = targetSampleRate;
        FrameDurationMs = frameDurationMs;
        AudioBufferMilliseconds = audioBufferMilliseconds;
        Application = application;
    }

    public int? TargetSampleRate { get; }

    public int FrameDurationMs { get; }

    public int FramesPerSecond => Math.Max(1, (int)Math.Round(1000d / FrameDurationMs));

    public int AudioBufferMilliseconds { get; }

    public UdpOpusApplication Application { get; }

    public int ResolveFrameSamples(int sampleRate)
    {
        if (sampleRate <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(sampleRate));
        }

        return Math.Max(1, (int)Math.Round(sampleRate * (FrameDurationMs / 1000d)));
    }
}

internal sealed class LowLatencyWasapiLoopbackCapture : WasapiCapture
{
    public LowLatencyWasapiLoopbackCapture(int audioBufferMilliseconds)
        : this(GetDefaultLoopbackCaptureDevice(), audioBufferMilliseconds)
    {
    }

    private LowLatencyWasapiLoopbackCapture(MMDevice captureDevice, int audioBufferMilliseconds)
        : base(captureDevice, useEventSync: true, audioBufferMillisecondsLength: audioBufferMilliseconds)
    {
    }

    private static MMDevice GetDefaultLoopbackCaptureDevice()
    {
        using var devices = new MMDeviceEnumerator();
        return devices.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
    }

    protected override AudioClientStreamFlags GetAudioClientStreamFlags()
    {
        return AudioClientStreamFlags.Loopback | base.GetAudioClientStreamFlags();
    }
}
