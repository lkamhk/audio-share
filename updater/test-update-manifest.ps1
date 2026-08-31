[CmdletBinding()]
param(
    [ValidateSet("windows", "android", "all")]
    [string]$Target = "all",
    [string]$CurrentVersion = "0.4.5",
    [switch]$RequireUpdate
)

if ($PSVersionTable.PSVersion.Major -lt 7) {
    $pwshCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
    $pwshCandidates = @(
        $(if ($pwshCommand) { $pwshCommand.Source }),
        (Join-Path $env:ProgramFiles "PowerShell\7\pwsh.exe"),
        (Join-Path $env:LOCALAPPDATA "Microsoft\WindowsApps\pwsh.exe")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) }
    $pwsh = $pwshCandidates | Select-Object -First 1
    if (-not $pwsh) {
        throw "PowerShell 7 or later is required."
    }
    $arguments = @(
        "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PSCommandPath,
        "-Target", $Target, "-CurrentVersion", $CurrentVersion
    )
    if ($RequireUpdate) { $arguments += "-RequireUpdate" }
    & $pwsh @arguments
    exit $LASTEXITCODE
}

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($CurrentVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw "CurrentVersion must use MAJOR.MINOR.PATCH."
}

$endpoint = "https://nfqkislweudltvckonog.supabase.co/functions/v1/audio-share-update"
$profiles = @{
    windows = @{ channel = "audio-share-server-stable"; arch = "x86_64" }
    android = @{ channel = "audio-share-android-stable"; arch = "universal" }
}
$targets = if ($Target -eq "all") { @("windows", "android") } else { @($Target) }

foreach ($name in $targets) {
    $profile = $profiles[$name]
    $requestUrl = "$endpoint`?channel=$([Uri]::EscapeDataString($profile.channel))" +
        "&target=$([Uri]::EscapeDataString($name))" +
        "&arch=$([Uri]::EscapeDataString($profile.arch))" +
        "&current_version=$([Uri]::EscapeDataString($CurrentVersion))"

    $response = Invoke-WebRequest -Method Get -Uri $requestUrl -SkipHttpErrorCheck
    if ($response.StatusCode -eq 204) {
        Write-Host "$name`: no update (HTTP 204)"
        if ($RequireUpdate) { throw "$name did not return an update." }
        continue
    }
    if ($response.StatusCode -ne 200) {
        throw "$name returned HTTP $($response.StatusCode): $($response.Content)"
    }

    $manifest = $response.Content | ConvertFrom-Json
    if ([string]$manifest.version -notmatch '^\d+\.\d+\.\d+$') {
        throw "$name returned an invalid version."
    }
    $downloadUrl = [Uri][string]$manifest.url
    if ($downloadUrl.Scheme -ne "https") {
        throw "$name returned a non-HTTPS download URL."
    }
    $signature = [Convert]::FromBase64String([string]$manifest.signature)
    if ($signature.Length -ne 64) {
        throw "$name returned a signature that is not ECDSA P-256 P1363."
    }
    Write-Host "$name`: update $($manifest.version) is available (HTTP 200)"
    Write-Host "  URL: $downloadUrl"
}
