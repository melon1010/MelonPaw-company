param(
    [string]$Image = "melonpaw-company-sandbox:latest"
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$dockerfile = Join-Path $workspace "docker\sandbox\Dockerfile"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required for enterprise shell isolation. Install Docker before building the sandbox image."
}

docker build --tag $Image --file $dockerfile (Split-Path -Parent $dockerfile)
if ($LASTEXITCODE -ne 0) {
    throw "Failed to build AgentScope sandbox image: $Image"
}

[PSCustomObject]@{
    Image = $Image
    Mode = "AgentScope DockerFilesystemSpec / IsolationScope.USER"
}
