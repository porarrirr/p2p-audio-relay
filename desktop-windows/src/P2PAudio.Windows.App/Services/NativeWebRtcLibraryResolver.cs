using System.ComponentModel;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace P2PAudio.Windows.App.Services;

internal static class NativeBridgeModuleInitializer
{
    [ModuleInitializer]
    internal static void Initialize()
    {
        NativeWebRtcLibraryResolver.EnsureRegistered();
    }
}

internal static class NativeWebRtcLibraryResolver
{
    private const string DllBaseName = "p2paudio_core_webrtc";
    private const string DllFileName = $"{DllBaseName}.dll";
    private static readonly NativeLibrarySpec WebRtcLibrary = new(
        BaseName: DllBaseName,
        FileName: DllFileName,
        RequiredRuntimeFiles:
        [
            "msvcp140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "libcrypto-3-x64.dll",
            "libssl-3-x64.dll",
            "juice.dll",
            "srtp2.dll",
            "zlib1.dll",
            "legacy.dll",
            "datachannel.dll",
            "p2paudio_core_webrtc.dll"
        ],
        PreloadDependencyFiles:
        [
            "msvcp140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "libcrypto-3-x64.dll",
            "libssl-3-x64.dll",
            "juice.dll",
            "srtp2.dll",
            "zlib1.dll",
            "legacy.dll",
            "datachannel.dll"
        ]
    );
    private static readonly NativeLibrarySpec UdpOpusLibrary = new(
        BaseName: NativeUdpOpusLibraryResolver.DllBaseName,
        FileName: NativeUdpOpusLibraryResolver.DllFileName,
        RequiredRuntimeFiles:
        [
            "msvcp140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "opus.dll",
            "portaudio.dll",
            NativeUdpOpusLibraryResolver.DllFileName
        ],
        PreloadDependencyFiles:
        [
            "msvcp140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "opus.dll",
            "portaudio.dll"
        ]
    );
    private static readonly NativeLibrarySpec[] Libraries = [WebRtcLibrary, UdpOpusLibrary];

    private static readonly object Sync = new();
    private static readonly HashSet<string> RegisteredRuntimeDirectories = new(StringComparer.OrdinalIgnoreCase);
    private static readonly List<nint> RuntimeDirectoryCookies = [];
    private static readonly Dictionary<string, Exception> LastLoadFailures = new(StringComparer.OrdinalIgnoreCase);
    private static readonly Dictionary<string, nint> LoadedDependencyHandles = new(StringComparer.OrdinalIgnoreCase);
    private static bool _resolverRegistered;
    private static bool _defaultDllDirectoriesConfigured;
    private static readonly Dictionary<string, nint> ResolvedHandles = new(StringComparer.OrdinalIgnoreCase);

    public static void EnsureRegistered()
    {
        if (_resolverRegistered)
        {
            return;
        }

        lock (Sync)
        {
            if (_resolverRegistered)
            {
                return;
            }

            NativeLibrary.SetDllImportResolver(typeof(NativeWebRtcLibraryResolver).Assembly, Resolve);
            _resolverRegistered = true;
        }
    }

    internal static bool IsNativeLoadFailure(Exception exception)
    {
        var unwrapped = Unwrap(exception);
        return unwrapped is DllNotFoundException or BadImageFormatException ||
               Libraries.Any(spec => unwrapped.Message.Contains(spec.BaseName, StringComparison.OrdinalIgnoreCase));
    }

    internal static string DescribeStartupFailure(Exception exception, string? baseDirectory = null)
        => DescribeStartupFailure(WebRtcLibrary.BaseName, exception, baseDirectory);

    internal static string DescribeStartupFailure(string dllBaseName, Exception exception, string? baseDirectory = null)
    {
        var unwrapped = Unwrap(exception);
        var library = GetLibrarySpec(dllBaseName);
        var root = baseDirectory ?? AppContext.BaseDirectory;
        var runtimeDirectory = Path.Combine(root, "runtimes", "win-x64", "native");

        if (!Directory.Exists(runtimeDirectory))
        {
            return $"ネイティブ DLL フォルダーが見つかりません: {runtimeDirectory}。{unwrapped.Message}";
        }

        var missingFiles = library.RequiredRuntimeFiles
            .Where(fileName => !File.Exists(Path.Combine(runtimeDirectory, fileName)))
            .ToArray();

        if (missingFiles.Length > 0)
        {
            return $"必要なネイティブ DLL が不足しています: {string.Join(", ", missingFiles)}。配置先: {runtimeDirectory}。{unwrapped.Message}";
        }

        if (LastLoadFailures.TryGetValue(library.BaseName, out var loadFailure))
        {
            return $"ネイティブ DLL は見つかりましたが読み込めませんでした。配置先: {runtimeDirectory}。{Unwrap(loadFailure).Message}";
        }

        return $"ネイティブ DLL は見つかりましたが読み込めませんでした。配置先: {runtimeDirectory}。{unwrapped.Message}";
    }

    internal static string? ResolveLibraryPath(string? baseDirectory = null)
        => ResolveLibraryPath(WebRtcLibrary.BaseName, baseDirectory);

    internal static string? ResolveLibraryPath(string dllBaseName, string? baseDirectory = null)
    {
        var library = GetLibrarySpec(dllBaseName);
        var root = baseDirectory ?? AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(root, "runtimes", "win-x64", "native", library.FileName),
            Path.Combine(root, library.FileName)
        };

        return candidates.FirstOrDefault(File.Exists);
    }

    internal static IReadOnlyList<string> GetDependencyLoadPaths(string dllBaseName, string? baseDirectory = null)
    {
        var library = GetLibrarySpec(dllBaseName);
        var libraryPath = ResolveLibraryPath(dllBaseName, baseDirectory);
        if (string.IsNullOrWhiteSpace(libraryPath))
        {
            return [];
        }

        var libraryDirectory = Path.GetDirectoryName(libraryPath);
        if (string.IsNullOrWhiteSpace(libraryDirectory))
        {
            return [];
        }

        return library.PreloadDependencyFiles
            .Select(fileName => Path.Combine(libraryDirectory, fileName))
            .Where(File.Exists)
            .ToArray();
    }

    internal static void EnsureLoaded(string dllBaseName, string? baseDirectory = null)
    {
        var library = GetLibrarySpec(dllBaseName);
        var root = baseDirectory ?? AppContext.BaseDirectory;

        lock (Sync)
        {
            _ = EnsureLibraryLoaded(library, root);
        }
    }

    private static nint Resolve(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
    {
        _ = assembly;
        _ = searchPath;

        var library = Libraries.FirstOrDefault(spec =>
            string.Equals(libraryName, spec.BaseName, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(libraryName, spec.FileName, StringComparison.OrdinalIgnoreCase));
        if (library is null)
        {
            return 0;
        }

        lock (Sync)
        {
            try
            {
                return EnsureLibraryLoaded(library, AppContext.BaseDirectory);
            }
            catch (Exception ex)
            {
                LastLoadFailures[library.BaseName] = ex;
                return 0;
            }
        }
    }

    private static nint EnsureLibraryLoaded(NativeLibrarySpec library, string root)
    {
        if (ResolvedHandles.TryGetValue(library.BaseName, out var resolvedHandle) && resolvedHandle != 0)
        {
            return resolvedHandle;
        }

        var libraryPath = ResolveLibraryPath(library.BaseName, root);
        if (string.IsNullOrWhiteSpace(libraryPath))
        {
            throw new DllNotFoundException($"Unable to locate '{library.FileName}' under '{root}'.");
        }

        var libraryDirectory = Path.GetDirectoryName(libraryPath);
        if (!string.IsNullOrWhiteSpace(libraryDirectory))
        {
            EnsureRuntimeDirectoryRegistered(libraryDirectory);
        }

        LoadDependencyLibraries(library, libraryDirectory);
        resolvedHandle = LoadLibraryFromPath(libraryPath);
        ResolvedHandles[library.BaseName] = resolvedHandle;
        LastLoadFailures.Remove(library.BaseName);
        return resolvedHandle;
    }

    private static void LoadDependencyLibraries(NativeLibrarySpec library, string? libraryDirectory)
    {
        if (string.IsNullOrWhiteSpace(libraryDirectory))
        {
            return;
        }

        foreach (var dependencyPath in library.PreloadDependencyFiles
                     .Select(fileName => Path.Combine(libraryDirectory, fileName))
                     .Where(File.Exists))
        {
            if (LoadedDependencyHandles.ContainsKey(dependencyPath))
            {
                continue;
            }

            LoadedDependencyHandles[dependencyPath] = LoadLibraryFromPath(dependencyPath);
        }
    }

    private static void EnsureRuntimeDirectoryRegistered(string runtimeDirectory)
    {
        if (RegisteredRuntimeDirectories.Contains(runtimeDirectory))
        {
            return;
        }

        EnsureDefaultDllDirectoriesConfigured();

        var cookie = AddDllDirectory(runtimeDirectory);
        if (cookie == 0)
        {
            throw new InvalidOperationException(
                $"Failed to register native DLL directory '{runtimeDirectory}': {new Win32Exception(Marshal.GetLastWin32Error()).Message}");
        }

        RegisteredRuntimeDirectories.Add(runtimeDirectory);
        RuntimeDirectoryCookies.Add(cookie);
    }

    private static void EnsureDefaultDllDirectoriesConfigured()
    {
        if (_defaultDllDirectoriesConfigured)
        {
            return;
        }

        if (!SetDefaultDllDirectories(LoadLibrarySearchDefaultDirs))
        {
            throw new InvalidOperationException(
                $"Failed to configure DLL search paths: {new Win32Exception(Marshal.GetLastWin32Error()).Message}");
        }

        _defaultDllDirectoriesConfigured = true;
    }

    private static nint LoadLibraryFromPath(string libraryPath)
    {
        var handle = LoadLibraryEx(libraryPath, nint.Zero, LoadLibrarySearchDllLoadDir | LoadLibrarySearchDefaultDirs);
        if (handle != 0)
        {
            return handle;
        }

        var error = Marshal.GetLastWin32Error();
        throw new DllNotFoundException(
            $"Unable to load '{Path.GetFileName(libraryPath)}' from '{libraryPath}': {new Win32Exception(error).Message} (0x{error:X8})");
    }

    private static NativeLibrarySpec GetLibrarySpec(string dllBaseName)
    {
        return Libraries.First(spec => string.Equals(spec.BaseName, dllBaseName, StringComparison.OrdinalIgnoreCase));
    }

    private static Exception Unwrap(Exception exception)
    {
        if (exception is TypeInitializationException typeInitialization)
        {
            var inner = typeInitialization.InnerException;
            if (inner is not null)
            {
                return Unwrap(inner);
            }
        }

        return exception;
    }

    private sealed record NativeLibrarySpec(
        string BaseName,
        string FileName,
        IReadOnlyList<string> RequiredRuntimeFiles,
        IReadOnlyList<string> PreloadDependencyFiles
    );

    private const uint LoadLibrarySearchDefaultDirs = 0x00001000;
    private const uint LoadLibrarySearchDllLoadDir = 0x00000100;

    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern nint AddDllDirectory(string newDirectory);

    [DllImport("kernel32", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetDefaultDllDirectories(uint directoryFlags);

    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern nint LoadLibraryEx(string lpFileName, nint hFile, uint dwFlags);
}
