using System.Diagnostics;

namespace P2PAudio.Windows.App.Services;

public sealed class AudioFrameTimingClock
{
    private double _nextTimestampMs;
    private double _nextDueAtTickMs;
    private double _nextDueAtTimestampTicks;

    public void Reset(long startTimestampMs, long startDueAtTickMs, long? startDueAtTimestampTicks = null)
    {
        _nextTimestampMs = startTimestampMs;
        _nextDueAtTickMs = startDueAtTickMs;
        _nextDueAtTimestampTicks = startDueAtTimestampTicks ?? Stopwatch.GetTimestamp();
    }

    public ScheduledFrameTiming Next(int sampleRate, int frameSamplesPerChannel)
    {
        if (sampleRate <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(sampleRate));
        }

        if (frameSamplesPerChannel <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(frameSamplesPerChannel));
        }

        var exactFrameDurationMs = frameSamplesPerChannel * 1000d / sampleRate;
        var frameDurationMs = Math.Max(1, (int)Math.Round(exactFrameDurationMs));
        var exactDueAtTimestampTicks = frameSamplesPerChannel * (double)Stopwatch.Frequency / sampleRate;
        var timing = new ScheduledFrameTiming(
            TimestampMs: (long)Math.Round(_nextTimestampMs),
            DueAtTickMs: (long)Math.Round(_nextDueAtTickMs),
            DueAtTimestampTicks: (long)Math.Round(_nextDueAtTimestampTicks),
            FrameDurationMs: frameDurationMs
        );
        _nextTimestampMs += exactFrameDurationMs;
        _nextDueAtTickMs += exactFrameDurationMs;
        _nextDueAtTimestampTicks += exactDueAtTimestampTicks;
        return timing;
    }
}

public readonly record struct ScheduledFrameTiming(
    long TimestampMs,
    long DueAtTickMs,
    long DueAtTimestampTicks,
    int FrameDurationMs
);
