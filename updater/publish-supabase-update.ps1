[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ZipUrl,

    [Parameter(Mandatory)]
    [string]$SignaturePath,

    [string]$Version = "",
    [string]$NotesPath = "",
    [ValidateSet("windows", "android")]
    [string]$Target = "windows",
    [switch]$Enable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $projectRoot "settings\updater.json"
if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    throw "Create settings/updater.json from settings/updater.example.json before publishing."
}
$settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$projectRef = [string]$settings.supabaseProjectRef
$channel = if ($Target -eq "android") {
    "audio-share-android-stable"
} else {
    [string]$settings.channel
}

if ($projectRef -notmatch '^[a-z0-9]{20}$') {
    throw "supabaseProjectRef must be a 20-character project reference."
}
if ($channel -notin @("audio-share-server-stable", "audio-share-android-stable")) {
    throw "The updater channel is not supported."
}
if ($ZipUrl -notmatch '^https://(?:www\.)?dropbox\.com/') {
    throw "ZipUrl must be a public Dropbox HTTPS shared link."
}
$uri = [UriBuilder]$ZipUrl
$queryParts = [Collections.Generic.List[string]]::new()
foreach ($part in $uri.Query.TrimStart('?').Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
    $key = [Uri]::UnescapeDataString(($part -split '=', 2)[0])
    if ($key -notin @('raw', 'dl')) {
        $queryParts.Add($part)
    }
}
$queryParts.Add('dl=1')
$uri.Query = $queryParts -join '&'
$normalizedZipUrl = $uri.Uri.AbsoluteUri

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = (Get-Content -LiteralPath (Join-Path $projectRoot ".ver") -Raw -Encoding UTF8).Trim()
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must use MAJOR.MINOR.PATCH."
}

$resolvedSignature = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($SignaturePath)
$signature = (Get-Content -LiteralPath $resolvedSignature -Raw -Encoding UTF8).Trim()
try {
    $signatureBytes = [Convert]::FromBase64String($signature)
} catch {
    throw "SignaturePath does not contain valid Base64."
}
if ($signatureBytes.Length -ne 64) {
    throw "The detached ECDSA P-256 signature must contain exactly 64 bytes."
}

$productName = if ($Target -eq "android") { "Audio Share Android" } else { "Audio Share Server" }
$notes = "$productName v$Version"
if (-not [string]::IsNullOrWhiteSpace($NotesPath)) {
    $resolvedNotes = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($NotesPath)
    $notes = Get-Content -LiteralPath $resolvedNotes -Raw -Encoding UTF8
}

$secretKey = $env:AUDIO_SHARE_SUPABASE_SECRET_KEY
if ([string]::IsNullOrWhiteSpace($secretKey)) {
    throw "Set AUDIO_SHARE_SUPABASE_SECRET_KEY in the current process before publishing."
}
$headers = @{
    apikey = $secretKey
    Prefer = "resolution=merge-duplicates,return=representation"
}
if (-not $secretKey.StartsWith("sb_")) {
    $headers.Authorization = "Bearer $secretKey"
}

$payload = @{
    channel = $channel
    latest_version = $Version
    zip_url = $normalizedZipUrl
    signature = $signature
    notes = $notes
    published_at = [DateTime]::UtcNow.ToString("o")
    enabled = [bool]$Enable
} | ConvertTo-Json -Depth 5

$requestUrl = "https://$projectRef.supabase.co/rest/v1/audio_share_updates?on_conflict=channel"
$response = Invoke-RestMethod -Method Post -Uri $requestUrl -Headers $headers `
    -ContentType "application/json; charset=utf-8" -Body $payload

$state = if ($Enable) { "enabled" } else { "disabled" }
Write-Host "Published $productName v$Version to $channel in the $state state."
$response | ConvertTo-Json -Depth 5
