using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;
using P2PAudio.Windows.App.Logging;

namespace P2PAudio.Windows.App;

internal static class StartMenuShortcut
{
    private const string ShortcutName = "P2PAudio.lnk";
    private const string AppName = "P2PAudio";
    private const string LauncherScriptName = "run-app.ps1";
    private static readonly string PowerShellExecutablePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.System),
        "WindowsPowerShell",
        "v1.0",
        "powershell.exe");

    internal static void EnsureStartMenuShortcut()
    {
        try
        {
            var shortcutRoots = GetShortcutRoots();
            if (shortcutRoots.Count == 0)
            {
                AppLogger.W(
                    "StartMenuShortcut",
                    "programs_path_unavailable",
                    "Start menu programs path is unavailable");
                return;
            }

            var executablePath = GetExecutablePath();
            if (!IsExecutablePath(executablePath) || !File.Exists(executablePath))
            {
                AppLogger.W(
                    "StartMenuShortcut",
                    "executable_path_unavailable",
                    "Current executable path is unavailable");
                return;
            }

            var shortcutPaths = shortcutRoots
                .SelectMany(GetShortcutPathsToRepair)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray();

            if (shortcutPaths.Length == 0)
            {
                AppLogger.W(
                    "StartMenuShortcut",
                    "shortcut_paths_unavailable",
                    "No Start Menu shortcut paths were available");
                return;
            }

            foreach (var shortcutPath in shortcutPaths)
            {
                var desiredShortcut = CreateShortcutDefinition(shortcutPath, executablePath);
                var existingShortcut = TryReadShortcut(shortcutPath);

                if (!ShouldUpdateShortcut(existingShortcut, desiredShortcut))
                {
                    continue;
                }

                CreateOrUpdateShortcut(desiredShortcut);
                AppLogger.I(
                    "StartMenuShortcut",
                    "shortcut_updated",
                    "Start Menu shortcut updated",
                    new Dictionary<string, object?>
                    {
                        ["shortcutPath"] = shortcutPath,
                        ["targetPath"] = desiredShortcut.TargetPath
                    });
            }
        }
        catch (Exception ex)
        {
            AppLogger.E(
                "StartMenuShortcut",
                "ensure_failed",
                "Failed to ensure Start Menu shortcut",
                exception: ex);
        }
    }

    internal static ShortcutDefinition CreateShortcutDefinition(string shortcutPath, string currentExecutablePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(shortcutPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(currentExecutablePath);

        var launcherScriptPath = TryFindLauncherScriptPath(currentExecutablePath);
        if (launcherScriptPath is not null)
        {
            var launcherShortcut = TryCreateLauncherShortcutDefinition(shortcutPath, currentExecutablePath, launcherScriptPath);
            if (launcherShortcut is not null)
            {
                return launcherShortcut;
            }
        }

        return CreateDesiredShortcut(shortcutPath, currentExecutablePath);
    }

    internal static ShortcutDefinition CreateDesiredShortcut(string shortcutPath, string targetPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(shortcutPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(targetPath);

        var normalizedTargetPath = NormalizePath(targetPath)
            ?? throw new InvalidOperationException($"Could not normalize shortcut target '{targetPath}'.");
        var workingDirectory = Path.GetDirectoryName(normalizedTargetPath);

        if (string.IsNullOrWhiteSpace(workingDirectory))
        {
            throw new InvalidOperationException($"Could not determine a working directory for '{normalizedTargetPath}'.");
        }

        return new ShortcutDefinition(
            ShortcutPath: shortcutPath,
            TargetPath: normalizedTargetPath,
            WorkingDirectory: workingDirectory,
            IconLocation: $"{normalizedTargetPath},0",
            Arguments: string.Empty,
            Description: AppName
        );
    }

    internal static IReadOnlyList<string> GetShortcutPathsToRepair(string shortcutRootPath)
    {
        if (string.IsNullOrWhiteSpace(shortcutRootPath) || !Directory.Exists(shortcutRootPath))
        {
            return [];
        }

        var shortcutPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            Path.Combine(shortcutRootPath, ShortcutName)
        };

        foreach (var shortcutPath in Directory.EnumerateFiles(shortcutRootPath, ShortcutName, SearchOption.AllDirectories))
        {
            shortcutPaths.Add(shortcutPath);
        }

        return shortcutPaths.ToArray();
    }

    private static IReadOnlyList<string> GetShortcutRoots()
    {
        var roots = new List<string>(capacity: 2);
        AddShortcutRoot(Environment.GetFolderPath(Environment.SpecialFolder.Programs), roots);
        AddShortcutRoot(Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms), roots);
        return roots;
    }

    private static void AddShortcutRoot(string shortcutRootPath, ICollection<string> roots)
    {
        if (!string.IsNullOrWhiteSpace(shortcutRootPath) && Directory.Exists(shortcutRootPath))
        {
            roots.Add(shortcutRootPath);
        }
    }

    internal static string? TryFindLauncherScriptPath(string? startPath)
    {
        var normalizedStartPath = NormalizePath(startPath);
        if (normalizedStartPath is null)
        {
            return null;
        }

        var currentDirectory = Directory.Exists(normalizedStartPath)
            ? normalizedStartPath
            : Path.GetDirectoryName(normalizedStartPath);

        while (!string.IsNullOrWhiteSpace(currentDirectory))
        {
            var launcherScriptPath = Path.Combine(currentDirectory, LauncherScriptName);
            if (File.Exists(launcherScriptPath))
            {
                return launcherScriptPath;
            }

            var parentDirectory = Directory.GetParent(currentDirectory)?.FullName;
            if (string.IsNullOrWhiteSpace(parentDirectory) ||
                string.Equals(parentDirectory, currentDirectory, StringComparison.OrdinalIgnoreCase))
            {
                break;
            }

            currentDirectory = parentDirectory;
        }

        return null;
    }

    internal static ShortcutDefinition? TryCreateLauncherShortcutDefinition(
        string shortcutPath,
        string currentExecutablePath,
        string launcherScriptPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(shortcutPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(currentExecutablePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(launcherScriptPath);

        var normalizedCurrentExecutablePath = NormalizePath(currentExecutablePath)
            ?? throw new InvalidOperationException($"Could not normalize shortcut icon source '{currentExecutablePath}'.");
        var normalizedLauncherScriptPath = NormalizePath(launcherScriptPath)
            ?? throw new InvalidOperationException($"Could not normalize launcher script path '{launcherScriptPath}'.");
        var workingDirectory = Path.GetDirectoryName(normalizedLauncherScriptPath);

        if (string.IsNullOrWhiteSpace(workingDirectory))
        {
            throw new InvalidOperationException($"Could not determine a working directory for '{normalizedLauncherScriptPath}'.");
        }

        var powerShellPath = ResolvePowerShellExecutablePath();
        if (powerShellPath is null)
        {
            AppLogger.W(
                "StartMenuShortcut",
                "powershell_unavailable",
                "PowerShell executable could not be located for the launcher shortcut");
            return null;
        }

        return new ShortcutDefinition(
            ShortcutPath: shortcutPath,
            TargetPath: powerShellPath,
            WorkingDirectory: workingDirectory,
            IconLocation: $"{normalizedCurrentExecutablePath},0",
            Arguments: $"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{normalizedLauncherScriptPath}\"",
            Description: AppName
        );
    }

    internal static bool ShouldUpdateShortcut(ShortcutSnapshot? existingShortcut, ShortcutDefinition desiredShortcut)
    {
        ArgumentNullException.ThrowIfNull(desiredShortcut);

        if (existingShortcut is null)
        {
            return true;
        }

        if (!PathPointsToExistingFile(existingShortcut.TargetPath))
        {
            return true;
        }

        return !PathsMatch(existingShortcut.TargetPath, desiredShortcut.TargetPath) ||
               !PathsMatch(existingShortcut.WorkingDirectory, desiredShortcut.WorkingDirectory) ||
               !string.Equals(existingShortcut.Arguments?.Trim(), desiredShortcut.Arguments, StringComparison.Ordinal) ||
               !string.Equals(existingShortcut.Description?.Trim(), desiredShortcut.Description, StringComparison.OrdinalIgnoreCase) ||
               !string.Equals(NormalizeIconLocation(existingShortcut.IconLocation), NormalizeIconLocation(desiredShortcut.IconLocation), StringComparison.OrdinalIgnoreCase);
    }

    private static string? GetExecutablePath()
    {
        var processPath = Environment.ProcessPath;
        if (IsExecutablePath(processPath))
        {
            return processPath;
        }

        using var process = Process.GetCurrentProcess();
        var mainModulePath = process.MainModule?.FileName;
        if (IsExecutablePath(mainModulePath))
        {
            return mainModulePath;
        }

        var assemblyLocation = Assembly.GetExecutingAssembly().Location;
        if (IsExecutablePath(assemblyLocation))
        {
            return assemblyLocation;
        }

        return null;
    }

    private static bool IsExecutablePath(string? path) =>
        !string.IsNullOrWhiteSpace(path) &&
        string.Equals(Path.GetExtension(path), ".exe", StringComparison.OrdinalIgnoreCase);

    private static string? ResolvePowerShellExecutablePath()
    {
        var systemDirectory = Environment.GetFolderPath(Environment.SpecialFolder.System);
        if (string.IsNullOrWhiteSpace(systemDirectory))
        {
            return null;
        }

        return File.Exists(PowerShellExecutablePath)
            ? PowerShellExecutablePath
            : null;
    }

    private static ShortcutSnapshot? TryReadShortcut(string shortcutPath)
    {
        if (!File.Exists(shortcutPath))
        {
            return null;
        }

        object? shell = null;
        object? shortcut = null;

        try
        {
            shell = CreateShell();
            shortcut = OpenShortcut(shell, shortcutPath);

            return new ShortcutSnapshot(
                TargetPath: GetShortcutProperty(shortcut, nameof(ShortcutSnapshot.TargetPath)),
                WorkingDirectory: GetShortcutProperty(shortcut, nameof(ShortcutSnapshot.WorkingDirectory)),
                IconLocation: GetShortcutProperty(shortcut, nameof(ShortcutSnapshot.IconLocation)),
                Arguments: GetShortcutProperty(shortcut, nameof(ShortcutSnapshot.Arguments)),
                Description: GetShortcutProperty(shortcut, nameof(ShortcutSnapshot.Description))
            );
        }
        catch (Exception ex)
        {
            AppLogger.E(
                "StartMenuShortcut",
                "inspect_failed",
                "Failed to inspect Start Menu shortcut",
                new Dictionary<string, object?>
                {
                    ["shortcutPath"] = shortcutPath
                },
                ex);
            return null;
        }
        finally
        {
            ReleaseComObject(shortcut);
            ReleaseComObject(shell);
        }
    }

    private static void CreateOrUpdateShortcut(ShortcutDefinition shortcutDefinition)
    {
        var shortcutDirectory = Path.GetDirectoryName(shortcutDefinition.ShortcutPath);
        if (string.IsNullOrWhiteSpace(shortcutDirectory))
        {
            throw new InvalidOperationException($"Could not determine a shortcut directory for '{shortcutDefinition.ShortcutPath}'.");
        }

        Directory.CreateDirectory(shortcutDirectory);

        object? shell = null;
        object? shortcut = null;
        try
        {
            shell = CreateShell();
            shortcut = OpenShortcut(shell, shortcutDefinition.ShortcutPath);
            SetShortcutProperty(shortcut, nameof(ShortcutSnapshot.TargetPath), shortcutDefinition.TargetPath);
            SetShortcutProperty(shortcut, nameof(ShortcutSnapshot.WorkingDirectory), shortcutDefinition.WorkingDirectory);
            SetShortcutProperty(shortcut, nameof(ShortcutSnapshot.IconLocation), shortcutDefinition.IconLocation);
            SetShortcutProperty(shortcut, nameof(ShortcutSnapshot.Arguments), shortcutDefinition.Arguments);
            SetShortcutProperty(shortcut, nameof(ShortcutSnapshot.Description), shortcutDefinition.Description);
            SaveShortcut(shortcut);
        }
        finally
        {
            ReleaseComObject(shortcut);
            ReleaseComObject(shell);
        }
    }

    private static object CreateShell()
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell")
            ?? throw new InvalidOperationException("WScript.Shell COM type is unavailable.");

        return Activator.CreateInstance(shellType)
            ?? throw new InvalidOperationException("Failed to create the WScript.Shell COM object.");
    }

    private static object OpenShortcut(object shell, string shortcutPath) =>
        shell.GetType().InvokeMember(
            "CreateShortcut",
            BindingFlags.InvokeMethod,
            binder: null,
            target: shell,
            args: [shortcutPath]) ??
        throw new InvalidOperationException($"Failed to open shortcut '{shortcutPath}'.");

    private static string? GetShortcutProperty(object shortcut, string propertyName) =>
        shortcut.GetType().InvokeMember(
            propertyName,
            BindingFlags.GetProperty,
            binder: null,
            target: shortcut,
            args: null) as string;

    private static void SetShortcutProperty(object shortcut, string propertyName, string value)
    {
        shortcut.GetType().InvokeMember(
            propertyName,
            BindingFlags.SetProperty,
            binder: null,
            target: shortcut,
            args: [value]);
    }

    private static void SaveShortcut(object shortcut)
    {
        shortcut.GetType().InvokeMember(
            "Save",
            BindingFlags.InvokeMethod,
            binder: null,
            target: shortcut,
            args: null);
    }

    private static void ReleaseComObject(object? comObject)
    {
        if (comObject is not null && Marshal.IsComObject(comObject))
        {
            _ = Marshal.FinalReleaseComObject(comObject);
        }
    }

    private static bool PathPointsToExistingFile(string? path) =>
        NormalizePath(path) is { } normalizedPath && File.Exists(normalizedPath);

    private static bool PathsMatch(string? left, string? right) =>
        NormalizePath(left) is { } normalizedLeft &&
        NormalizePath(right) is { } normalizedRight &&
        string.Equals(normalizedLeft, normalizedRight, StringComparison.OrdinalIgnoreCase);

    private static string NormalizeIconLocation(string? iconLocation)
    {
        if (string.IsNullOrWhiteSpace(iconLocation))
        {
            return string.Empty;
        }

        var parts = iconLocation.Split(',', 2, StringSplitOptions.TrimEntries);
        var normalizedPath = NormalizePath(parts[0]);
        if (normalizedPath is null)
        {
            return string.Empty;
        }

        var iconIndex = parts.Length > 1 && int.TryParse(parts[1], out var parsedIndex)
            ? parsedIndex
            : 0;

        return $"{normalizedPath},{iconIndex}";
    }

    private static string? NormalizePath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return null;
        }

        try
        {
            return Path.GetFullPath(path.Trim().Trim('"'));
        }
        catch (ArgumentException)
        {
            return null;
        }
        catch (NotSupportedException)
        {
            return null;
        }
        catch (PathTooLongException)
        {
            return null;
        }
        catch (System.Security.SecurityException)
        {
            return null;
        }
    }

    internal sealed record ShortcutDefinition(
        string ShortcutPath,
        string TargetPath,
        string WorkingDirectory,
        string IconLocation,
        string Arguments,
        string Description);

    internal sealed record ShortcutSnapshot(
        string? TargetPath,
        string? WorkingDirectory,
        string? IconLocation,
        string? Arguments,
        string? Description);
}
