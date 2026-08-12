using P2PAudio.Windows.App;

namespace P2PAudio.Windows.App.Tests;

public sealed class StartMenuShortcutTests
{
    [Fact]
    public void CreateDesiredShortcut_UsesExecutableDirectoryForLaunchMetadata()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var shortcutPath = Path.Combine(tempDirectory, "P2PAudio.lnk");
            var executablePath = Path.Combine(tempDirectory, "bin", "..", "bin", "P2PAudio.Windows.App.exe");

            var definition = StartMenuShortcut.CreateDesiredShortcut(shortcutPath, executablePath);

            var normalizedExecutablePath = Path.GetFullPath(executablePath);
            Assert.Equal(shortcutPath, definition.ShortcutPath);
            Assert.Equal(normalizedExecutablePath, definition.TargetPath);
            Assert.Equal(Path.GetDirectoryName(normalizedExecutablePath), definition.WorkingDirectory);
            Assert.Equal($"{normalizedExecutablePath},0", definition.IconLocation);
            Assert.Equal(string.Empty, definition.Arguments);
            Assert.Equal("P2PAudio", definition.Description);
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void ShouldUpdateShortcut_WhenShortcutIsMissing_ReturnsTrue()
    {
        var definition = StartMenuShortcut.CreateDesiredShortcut(
            shortcutPath: @"C:\Temp\P2PAudio.lnk",
            targetPath: @"C:\Temp\P2PAudio.Windows.App.exe");

        Assert.True(StartMenuShortcut.ShouldUpdateShortcut(existingShortcut: null, desiredShortcut: definition));
    }

    [Fact]
    public void ShouldUpdateShortcut_WhenExistingTargetDoesNotExist_ReturnsTrue()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var desiredTargetPath = Path.Combine(tempDirectory, "P2PAudio.Windows.App.exe");
            File.WriteAllBytes(desiredTargetPath, []);

            var definition = StartMenuShortcut.CreateDesiredShortcut(
                shortcutPath: Path.Combine(tempDirectory, "P2PAudio.lnk"),
                targetPath: desiredTargetPath);

            var existingShortcut = new StartMenuShortcut.ShortcutSnapshot(
                TargetPath: Path.Combine(tempDirectory, "missing", "P2PAudio.Windows.App.exe"),
                WorkingDirectory: definition.WorkingDirectory,
                IconLocation: definition.IconLocation,
                Arguments: definition.Arguments,
                Description: definition.Description);

            Assert.True(StartMenuShortcut.ShouldUpdateShortcut(existingShortcut, definition));
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void ShouldUpdateShortcut_WhenMetadataAlreadyMatches_ReturnsFalse()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var desiredTargetPath = Path.Combine(tempDirectory, "P2PAudio.Windows.App.exe");
            File.WriteAllBytes(desiredTargetPath, []);

            var definition = StartMenuShortcut.CreateDesiredShortcut(
                shortcutPath: Path.Combine(tempDirectory, "P2PAudio.lnk"),
                targetPath: desiredTargetPath);

            var existingShortcut = new StartMenuShortcut.ShortcutSnapshot(
                TargetPath: definition.TargetPath.ToUpperInvariant(),
                WorkingDirectory: definition.WorkingDirectory.ToUpperInvariant(),
                IconLocation: $"{definition.TargetPath.ToUpperInvariant()},0",
                Arguments: definition.Arguments,
                Description: definition.Description.ToLowerInvariant());

            Assert.False(StartMenuShortcut.ShouldUpdateShortcut(existingShortcut, definition));
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void ShouldUpdateShortcut_WhenWorkingDirectoryDiffers_ReturnsTrue()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var desiredTargetPath = Path.Combine(tempDirectory, "P2PAudio.Windows.App.exe");
            File.WriteAllBytes(desiredTargetPath, []);

            var definition = StartMenuShortcut.CreateDesiredShortcut(
                shortcutPath: Path.Combine(tempDirectory, "P2PAudio.lnk"),
                targetPath: desiredTargetPath);

            var existingShortcut = new StartMenuShortcut.ShortcutSnapshot(
                TargetPath: definition.TargetPath,
                WorkingDirectory: Path.Combine(tempDirectory, "other"),
                IconLocation: definition.IconLocation,
                Arguments: definition.Arguments,
                Description: definition.Description);

            Assert.True(StartMenuShortcut.ShouldUpdateShortcut(existingShortcut, definition));
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void ShouldUpdateShortcut_WhenArgumentsDiffer_ReturnsTrue()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var desiredTargetPath = Path.Combine(tempDirectory, "P2PAudio.Windows.App.exe");
            File.WriteAllBytes(desiredTargetPath, []);

            var definition = StartMenuShortcut.CreateDesiredShortcut(
                shortcutPath: Path.Combine(tempDirectory, "P2PAudio.lnk"),
                targetPath: desiredTargetPath);

            var existingShortcut = new StartMenuShortcut.ShortcutSnapshot(
                TargetPath: definition.TargetPath,
                WorkingDirectory: definition.WorkingDirectory,
                IconLocation: definition.IconLocation,
                Arguments: @"--app-path=""C:\Old\P2PAudio.Windows.App.exe""",
                Description: definition.Description);

            Assert.True(StartMenuShortcut.ShouldUpdateShortcut(existingShortcut, definition));
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void GetShortcutPathsToRepair_WhenMatchingShortcutsExist_ReturnsRootAndNestedMatches()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var rootShortcutPath = Path.Combine(tempDirectory, "P2PAudio.lnk");
            var nestedShortcutPath = Path.Combine(tempDirectory, "Nested", "More", "P2PAudio.lnk");
            var unrelatedShortcutPath = Path.Combine(tempDirectory, "Nested", "More", "Other.lnk");

            Directory.CreateDirectory(Path.GetDirectoryName(nestedShortcutPath)!);
            File.WriteAllBytes(rootShortcutPath, []);
            File.WriteAllBytes(nestedShortcutPath, []);
            File.WriteAllBytes(unrelatedShortcutPath, []);

            var shortcutPaths = StartMenuShortcut.GetShortcutPathsToRepair(tempDirectory);

            Assert.Contains(Path.GetFullPath(rootShortcutPath), shortcutPaths);
            Assert.Contains(Path.GetFullPath(nestedShortcutPath), shortcutPaths);
            Assert.DoesNotContain(Path.GetFullPath(unrelatedShortcutPath), shortcutPaths);
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void TryFindLauncherScriptPath_WhenRunAppScriptExists_ReturnsRootScriptPath()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var launcherScriptPath = Path.Combine(tempDirectory, "run-app.ps1");
            File.WriteAllText(launcherScriptPath, "Write-Host 'launcher'");

            var executablePath = Path.Combine(
                tempDirectory,
                "desktop-windows",
                "src",
                "P2PAudio.Windows.App",
                "bin",
                "Release",
                "net8.0-windows10.0.19041.0",
                "win-x64",
                "P2PAudio.Windows.App.exe");
            Directory.CreateDirectory(Path.GetDirectoryName(executablePath)!);
            File.WriteAllBytes(executablePath, []);

            Assert.Equal(launcherScriptPath, StartMenuShortcut.TryFindLauncherScriptPath(executablePath));
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    [Fact]
    public void CreateShortcutDefinition_WhenLauncherScriptExists_UsesPowerShellLauncher()
    {
        var tempDirectory = CreateTempDirectory();

        try
        {
            var launcherScriptPath = Path.Combine(tempDirectory, "run-app.ps1");
            File.WriteAllText(launcherScriptPath, "Write-Host 'launcher'");

            var executablePath = Path.Combine(
                tempDirectory,
                "desktop-windows",
                "src",
                "P2PAudio.Windows.App",
                "bin",
                "Release",
                "net8.0-windows10.0.19041.0",
                "win-x64",
                "P2PAudio.Windows.App.exe");
            Directory.CreateDirectory(Path.GetDirectoryName(executablePath)!);
            File.WriteAllBytes(executablePath, []);

            var shortcutPath = Path.Combine(tempDirectory, "Programs", "P2PAudio.lnk");

            var definition = StartMenuShortcut.CreateShortcutDefinition(shortcutPath, executablePath);
            var normalizedExecutablePath = Path.GetFullPath(executablePath);
            var normalizedLauncherScriptPath = Path.GetFullPath(launcherScriptPath);
            var expectedPowerShellPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.System),
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe");

            Assert.Equal(shortcutPath, definition.ShortcutPath);
            Assert.Equal(expectedPowerShellPath, definition.TargetPath);
            Assert.Equal(Path.GetDirectoryName(normalizedLauncherScriptPath), definition.WorkingDirectory);
            Assert.Equal($"{normalizedExecutablePath},0", definition.IconLocation);
            Assert.Equal(
                $"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{normalizedLauncherScriptPath}\"",
                definition.Arguments);
            Assert.Equal("P2PAudio", definition.Description);
        }
        finally
        {
            Directory.Delete(tempDirectory, recursive: true);
        }
    }

    private static string CreateTempDirectory()
    {
        var path = Path.Combine(Path.GetTempPath(), "p2paudio-shortcut-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }
}
