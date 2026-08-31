[CmdletBinding()]
param(
    [ValidateSet("All", "Android", "ServerCore", "ServerMfc")]
    [string]$Target = "All",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug"
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$jdkRoot = Join-Path $projectRoot "tools\jdk17\jdk-17.0.19+10"
$vcpkgRoot = Join-Path $projectRoot "tools\vcpkg"
$vsRoot = "C:\Program Files\Microsoft Visual Studio\2022\Community"
$vcVars = Join-Path $vsRoot "VC\Auxiliary\Build\vcvars64.bat"
$msBuild = Join-Path $vsRoot "MSBuild\Current\Bin\MSBuild.exe"

function Build-Android {
    if (Test-Path $jdkRoot) {
        $env:JAVA_HOME = $jdkRoot
        $env:Path = "$(Join-Path $jdkRoot 'bin');$env:Path"
    }
    $gradleTask = if ($Configuration -eq "Release") { "assembleRelease" } else { "assembleDebug" }
    Push-Location (Join-Path $projectRoot "android-app")
    try {
        & .\gradlew.bat --no-daemon --max-workers=1 $gradleTask
        if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

function Build-ServerCore {
    if (!(Test-Path $vcVars)) { throw "Visual Studio vcvars64.bat was not found at $vcVars." }
    $preset = "windows-$Configuration"
    $serverRoot = Join-Path $projectRoot "server-core"
    $command = "call `"$vcVars`" >nul && set `"VCPKG_ROOT=$vcpkgRoot`" && cd /d `"$serverRoot`" && cmake --preset $preset && cmake --build --preset $preset"
    & cmd.exe /d /c $command
    if ($LASTEXITCODE -ne 0) { throw "Server core build failed with exit code $LASTEXITCODE." }
}

function Build-ServerMfc {
    if (!(Test-Path $msBuild)) { throw "MSBuild was not found at $msBuild." }
    $solution = Join-Path $projectRoot "server-mfc\audio-share-server.sln"
    & $msBuild $solution /m:1 /restore "/p:Configuration=$Configuration" "/p:Platform=x64" "/p:VcpkgRoot=$vcpkgRoot\" "/p:VcpkgTriplet=x64-windows-static-md"
    if ($LASTEXITCODE -ne 0) { throw "MFC server build failed with exit code $LASTEXITCODE." }
}

switch ($Target) {
    "Android" { Build-Android }
    "ServerCore" { Build-ServerCore }
    "ServerMfc" { Build-ServerMfc }
    "All" {
        Build-Android
        Build-ServerCore
        Build-ServerMfc
    }
}
