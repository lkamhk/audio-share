[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArchivePath,

    [Parameter(Mandatory)]
    [string]$PrivateKeyPath,

    [string]$OutputPath = "",

    [Security.SecureString]$PrivateKeyPassword
)

if ($PSVersionTable.PSVersion.Major -lt 7) {
    if ($PrivateKeyPassword) {
        throw "PrivateKeyPassword can only be passed when this script is already running under PowerShell 7 or later."
    }
    $pwshCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
    $pwshCandidates = @(
        $(if ($pwshCommand) { $pwshCommand.Source }),
        (Join-Path $env:ProgramFiles "PowerShell\7\pwsh.exe"),
        (Join-Path $env:LOCALAPPDATA "Microsoft\WindowsApps\pwsh.exe")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) }
    $pwsh = $pwshCandidates | Select-Object -First 1
    if (-not $pwsh) {
        throw "PowerShell 7 or later is required. Install it with 'winget install --id Microsoft.PowerShell --source winget', reopen the terminal, then run this command again."
    }

    $forwardedArguments = @(
        "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PSCommandPath,
        "-ArchivePath", $ArchivePath,
        "-PrivateKeyPath", $PrivateKeyPath
    )
    if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
        $forwardedArguments += @("-OutputPath", $OutputPath)
    }
    & $pwsh @forwardedArguments
    exit $LASTEXITCODE
}

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$archive = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ArchivePath)
$privateKey = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($PrivateKeyPath)
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    throw "Update archive was not found: $archive"
}
if (-not (Test-Path -LiteralPath $privateKey -PathType Leaf)) {
    throw "Updater private key was not found: $privateKey"
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = "$archive.sig"
}
$signaturePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)

$password = if ($PrivateKeyPassword) {
    $PrivateKeyPassword
} else {
    Read-Host "Enter the updater private-key password" -AsSecureString
}
$passwordPointer = [IntPtr]::Zero
$plainPassword = $null
$ecdsa = $null

try {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $privatePem = Get-Content -LiteralPath $privateKey -Raw -Encoding UTF8
    $ecdsa = [Security.Cryptography.ECDsa]::Create()
    $ecdsa.ImportFromEncryptedPem($privatePem, $plainPassword)

    $archiveBytes = [IO.File]::ReadAllBytes($archive)
    $signature = $ecdsa.SignData(
        $archiveBytes,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::IeeeP1363FixedFieldConcatenation
    )
    if ($signature.Length -ne 64 -or -not $ecdsa.VerifyData(
            $archiveBytes,
            $signature,
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.DSASignatureFormat]::IeeeP1363FixedFieldConcatenation
        )) {
        throw "The generated ECDSA P-256 signature failed local verification."
    }

    $base64 = [Convert]::ToBase64String($signature)
    Set-Content -LiteralPath $signaturePath -Value $base64 -Encoding UTF8 -NoNewline
    Write-Host "Signed update archive: $archive"
    Write-Host "Detached signature: $signaturePath"
} finally {
    if ($ecdsa) {
        $ecdsa.Dispose()
    }
    $plainPassword = $null
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}
