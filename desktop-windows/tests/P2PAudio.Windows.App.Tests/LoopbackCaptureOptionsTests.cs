using P2PAudio.Windows.App.Services;

namespace P2PAudio.Windows.App.Tests;

public sealed class LoopbackCaptureOptionsTests
{
    [Fact]
    public void ResolveFrameSamples_UsesConfiguredFrameDuration()
    {
        var options = new LoopbackCaptureOptions(targetSampleRate: 48_000, frameDurationMs: 10);

        Assert.Equal(10, options.FrameDurationMs);
        Assert.Equal(100, options.FramesPerSecond);
        Assert.Equal(480, options.ResolveFrameSamples(48_000));
    }

    [Fact]
    public void ResolveFrameSamples_ForTwentyMilliseconds_ProducesLegacyFrameSize()
    {
        var options = new LoopbackCaptureOptions(targetSampleRate: 48_000, frameDurationMs: 20);

        Assert.Equal(50, options.FramesPerSecond);
        Assert.Equal(960, options.ResolveFrameSamples(48_000));
    }
}
