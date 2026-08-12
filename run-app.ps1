[CmdletBinding()]
param(
    [string]$AppPath
)

$ErrorActionPreference = 'Stop'

function Get-LatestWindowsAppPath {
    param(
        [string]$RepoRoot
    )

    $appProjectRoot = Join-Path (Join-Path (Join-Path $RepoRoot 'desktop-windows') 'src') 'P2PAudio.Windows.App'
    $binRoot = Join-Path $appProjectRoot 'bin'

    if (-not (Test-Path $binRoot)) {
        throw "Windows app build output was not found under '$binRoot'. Build the app first."
    }

    $candidate = Get-ChildItem -Path $binRoot -Filter 'P2PAudio.Windows.App.exe' -File -Recurse |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if ($null -eq $candidate) {
        throw "No built P2PAudio.Windows.App.exe was found under '$binRoot'."
    }

    return $candidate.FullName
}

$repoRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($AppPath)) {
    $AppPath = Get-LatestWindowsAppPath -RepoRoot $repoRoot
} else {
    $AppPath = [System.IO.Path]::GetFullPath($AppPath)
}

if (-not (Test-Path $AppPath)) {
    throw "Windows app executable was not found: $AppPath"
}

$workingDirectory = Split-Path -Parent $AppPath
Start-Process -FilePath $AppPath -WorkingDirectory $workingDirectory | Out-Null
