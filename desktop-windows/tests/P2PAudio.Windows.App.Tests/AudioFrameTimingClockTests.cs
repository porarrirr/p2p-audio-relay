using System.Diagnostics;
using P2PAudio.Windows.App.Services;

namespace P2PAudio.Windows.App.Tests;

public sealed class AudioFrameTimingClockTests
{
    [Fact]
    public void Next_UsesAudioFrameDurationForTimestampsAndDueTimes()
    {
        var clock = new AudioFrameTimingClock();
        clock.Reset(
            startTimestampMs: 10_000,
            startDueAtTickMs: 20_000,
            startDueAtTimestampTicks: 30_000);

        var first = clock.Next(sampleRate: 48_000, frameSamplesPerChannel: 960);
        var second = clock.Next(sampleRate: 48_000, frameSamplesPerChannel: 960);

        Assert.Equal(10_000, first.TimestampMs);
        Assert.Equal(20_000, first.DueAtTickMs);
        Assert.Equal(20, first.FrameDurationMs);
        Assert.Equal(10_020, second.TimestampMs);
        Assert.Equal(20_020, second.DueAtTickMs);
        Assert.Equal(30_000, first.DueAtTimestampTicks);
        Assert.Equal(30_000 + (long)Math.Round(20d * Stopwatch.Frequency / 1000d), second.DueAtTimestampTicks);
    }

    [Fact]
    public void Next_RoundsFrameDurationForCommon44100HzFrames()
    {
        var clock = new AudioFrameTimingClock();
        clock.Reset(startTimestampMs: 1_000, startDueAtTickMs: 2_000);

        var timing = clock.Next(sampleRate: 44_100, frameSamplesPerChannel: 882);

        Assert.Equal(20, timing.FrameDurationMs);
        Assert.Equal(1_000, timing.TimestampMs);
        Assert.Equal(2_000, timing.DueAtTickMs);
    }

    [Fact]
    public void Next_AccumulatesFractionalTimingWithoutDroppingSubMillisecondProgress()
    {
        var clock = new AudioFrameTimingClock();
        clock.Reset(
            startTimestampMs: 0,
            startDueAtTickMs: 0,
            startDueAtTimestampTicks: 0);

        var first = clock.Next(sampleRate: 44_100, frameSamplesPerChannel: 256);
        var second = clock.Next(sampleRate: 44_100, frameSamplesPerChannel: 256);
        var third = clock.Next(sampleRate: 44_100, frameSamplesPerChannel: 256);

        Assert.Equal(0, first.TimestampMs);
        Assert.True(second.TimestampMs > first.TimestampMs);
        Assert.True(third.DueAtTimestampTicks > second.DueAtTimestampTicks);
    }
}
