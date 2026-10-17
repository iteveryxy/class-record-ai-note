param([switch]$Demo, [int]$Port = 8080, [int]$WorkerPort = 8090)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if ($Port -lt 1024 -or $Port -gt 65535 -or $WorkerPort -lt 1024 -or $WorkerPort -gt 65535) { throw '端口须为 1024–65535' }
$dataRoot = Join-Path $taskRoot $(if ($Demo) { 'data/demo' } else { 'data/local' })
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$env:DATA_DIR = $dataRoot
$env:APP_DEMO = $Demo.IsPresent.ToString().ToLowerInvariant()
$env:PORT = $Port.ToString()
$env:ASR_WORKER_URL = "http://127.0.0.1:$WorkerPort"
if (-not $env:MODELSCOPE_CACHE) { $env:MODELSCOPE_CACHE = Join-Path $taskRoot '.tools/models' }
if (-not $env:HF_HOME) { $env:HF_HOME = Join-Path $taskRoot '.tools/huggingface' }
$runtimeDir = Join-Path $taskRoot 'artifacts/runtime'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$backendPom=[xml](Get-Content -LiteralPath (Join-Path $taskRoot 'backend/pom.xml') -Raw)
$jarPath = Join-Path $taskRoot "backend/target/class-notes-$($backendPom.project.version).jar"
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先运行 scripts/build.ps1' }
$runtimeJar = Join-Path $runtimeDir ('class-notes-' + [guid]::NewGuid().ToString('N') + '.jar')
Copy-Item -LiteralPath $jarPath -Destination $runtimeJar
$workerProcess = $null
if (-not $Demo) {
    $pythonPath = Join-Path $taskRoot 'worker/.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $pythonPath)) { throw '请先运行 scripts/setup.ps1 -WithAsr -PythonPath <Python3.12路径>' }
    $workerProcess = Start-Process -FilePath $pythonPath -ArgumentList @('-m','uvicorn','app:app','--host','127.0.0.1','--port',$WorkerPort.ToString()) -WorkingDirectory (Join-Path $taskRoot 'worker') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir "worker-$WorkerPort.log") -RedirectStandardError (Join-Path $runtimeDir "worker-$WorkerPort-error.log")
}
Write-Output "课记启动中：http://127.0.0.1:$Port （关闭此脚本会停止服务）"
try {
    Push-Location (Join-Path $taskRoot 'backend')
    try { & java --enable-native-access=ALL-UNNAMED -jar $runtimeJar; if ($LASTEXITCODE -ne 0) { throw 'Java 服务启动失败，请检查上方日志' } } finally { Pop-Location }
} finally {
    if ($workerProcess -and -not $workerProcess.HasExited) { Stop-Process -Id $workerProcess.Id -ErrorAction SilentlyContinue }
}
