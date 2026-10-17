$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $taskRoot 'frontend')
try {
    npm.cmd run test
    if ($LASTEXITCODE -ne 0) { throw '前端检查失败' }
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
} finally { Pop-Location }
& (Join-Path $PSScriptRoot 'maven.ps1') package -B
if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
Push-Location (Join-Path $taskRoot 'worker')
try {
    $testTempDir = Join-Path $taskRoot ('.tools/pytest-' + [guid]::NewGuid().ToString('N'))
    & '.\.venv\Scripts\python.exe' -m pytest -q --basetemp $testTempDir
    if ($LASTEXITCODE -ne 0) { throw 'Worker 检查失败' }
} finally { Pop-Location }
Write-Output '前端、后端与 Worker 检查已通过。'
