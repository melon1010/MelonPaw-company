param(
    [int]$Port = 8088,
    [string]$StateDir = "",
    [string]$JarPath = "",
    [string]$SandboxImage = "melonpaw-company-sandbox:latest",
    [string]$SandboxNetwork = "bridge",
    [switch]$SkipSandboxCheck,
    [int]$ReadyTimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $workspace "melon-app\target\melonpaw-company-web.jar"
}
$JarPath = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "Application JAR not found: $JarPath. Run the Maven package command first."
}

if ([string]::IsNullOrWhiteSpace($StateDir)) {
    $StateDir = $env:MELON_HOME
}
if ([string]::IsNullOrWhiteSpace($StateDir)) {
    $StateDir = Join-Path $HOME ".melonPaw-CompanyAI"
}
$StateDir = [System.IO.Path]::GetFullPath($StateDir)
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

$bootstrapTokenFile = if ([string]::IsNullOrWhiteSpace($env:MELON_BOOTSTRAP_TOKEN)) {
    Join-Path $StateDir "bootstrap.token"
} else {
    $null
}

$java = ""
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $java = Join-Path $env:JAVA_HOME "bin\java.exe"
}
if ([string]::IsNullOrWhiteSpace($java) -or -not (Test-Path -LiteralPath $java)) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw "Java 17 or newer is required. Set JAVA_HOME before starting MelonPaw-Company."
    }
    $java = $javaCommand.Source
}

$versionInfo = New-Object System.Diagnostics.ProcessStartInfo
$versionInfo.FileName = $java
$versionInfo.Arguments = "-version"
$versionInfo.UseShellExecute = $false
$versionInfo.CreateNoWindow = $true
$versionInfo.RedirectStandardOutput = $true
$versionInfo.RedirectStandardError = $true
$versionProcess = [System.Diagnostics.Process]::Start($versionInfo)
$javaVersion = $versionProcess.StandardError.ReadToEnd() + $versionProcess.StandardOutput.ReadToEnd()
$versionProcess.WaitForExit()
if ($versionProcess.ExitCode -ne 0 -or $javaVersion -notmatch 'version "(?:1\.)?(?<major>\d+)') {
    throw "Unable to determine the Java version from: $java"
}
if ([int]$Matches.major -lt 17) {
    throw "Java 17 or newer is required. Current runtime: $($Matches.major). Set JAVA_HOME to a supported JDK."
}

if (-not $SkipSandboxCheck) {
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $dockerCommand) {
        throw "Docker is required for Agent file and command execution. Install Docker or use -SkipSandboxCheck for UI-only configuration."
    }
    & $dockerCommand.Source version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker daemon is not available. Start Docker before launching MelonPaw-Company."
    }
    & $dockerCommand.Source image inspect $SandboxImage | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Sandbox image not found: $SandboxImage. Run scripts\build-sandbox.ps1 first."
    }
}

$pidFile = Join-Path $StateDir "melonpaw-company.pid"
if (Test-Path -LiteralPath $pidFile) {
    $existingPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($existingPid -match "^\d+$" -and (Get-Process -Id ([int]$existingPid) -ErrorAction SilentlyContinue)) {
        throw "MelonPaw-Company is already running with PID $existingPid."
    }
}

$applicationLog = Join-Path $StateDir "melonpaw-company.log"
$database = Join-Path $StateDir "enterprise.db"
$env:SERVER_ADDRESS = "127.0.0.1"
$env:SERVER_PORT = [string]$Port
$env:MELON_HOME = $StateDir
$env:MELON_ENTERPRISE_DATABASE_PATH = $database
$env:SPRING_PROFILES_ACTIVE = "docker"
$env:MELON_EXECUTION_SANDBOX = "docker"
$env:MELON_SANDBOX_IMAGE = $SandboxImage
$env:MELON_SANDBOX_NETWORK = $SandboxNetwork
$env:LOGGING_FILE_NAME = $applicationLog
$arguments = @("-Dlogging.file.name=$applicationLog", "-jar", $JarPath)

# ShellExecute inherits the native environment block without rebuilding it as
# a case-insensitive dictionary. This also works in hosts that expose both
# Path and PATH environment variables.
$quotedArguments = ($arguments | ForEach-Object {
    '"' + ($_ -replace '"', '\"') + '"'
}) -join " "
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $java
$startInfo.Arguments = $quotedArguments
$startInfo.WorkingDirectory = $workspace
$startInfo.UseShellExecute = $true
$startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$process = [System.Diagnostics.Process]::Start($startInfo)

Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ASCII

$healthUrl = "http://127.0.0.1:$Port/api/agent/health"
$deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
$ready = $false
while ([DateTime]::UtcNow -lt $deadline) {
    if ($process.HasExited) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        throw "MelonPaw-Company exited during startup. Check the log: $applicationLog"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Milliseconds 250
    }
}
if (-not $ready) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    throw "MelonPaw-Company did not become ready within $ReadyTimeoutSeconds seconds. Check the log: $applicationLog"
}

[PSCustomObject]@{
    Pid = $process.Id
    Url = "http://127.0.0.1:$Port"
    Health = $healthUrl
    StateDir = $StateDir
    Log = $applicationLog
    SandboxImage = $SandboxImage
    SandboxNetwork = $SandboxNetwork
    BootstrapTokenFile = $bootstrapTokenFile
}
