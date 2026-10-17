param([string]$PythonPath = 'python', [switch]$WithAsr)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath (Join-Path $toolsDir 'apache-maven-3.9.11/bin/mvn.cmd'))) {
    $mavenUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
    $archivePath = Join-Path $toolsDir 'maven.zip'
    Invoke-WebRequest -Uri $mavenUrl -OutFile $archivePath
    $expected = (Invoke-RestMethod -Uri "$mavenUrl.sha512").Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash -ne $expected) { throw 'Maven 校验失败' }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsDir -Force
}
$pythonVersion = & $PythonPath -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
if ($LASTEXITCODE -ne 0 -or $pythonVersion -notin @('3.11', '3.12')) { throw 'Worker 请使用 Python 3.11 或 3.12，示例：-PythonPath C:\Python312\python.exe' }
$workerVenv = Join-Path $taskRoot 'worker/.venv'
& $PythonPath -m venv $workerVenv
if ($LASTEXITCODE -ne 0) { throw '创建虚拟环境失败' }
$workerPython = Join-Path $workerVenv 'Scripts/python.exe'
if ($WithAsr) {
    & $workerPython -m pip install torch==2.9.1 torchaudio==2.9.1 --index-url https://download.pytorch.org/whl/cpu
    if ($LASTEXITCODE -ne 0) { throw 'PyTorch CPU 依赖安装失败' }
}
$requirementsFile = if ($WithAsr) { 'requirements-asr.txt' } else { 'requirements.txt' }
& $workerPython -m pip install -r (Join-Path $taskRoot "worker/$requirementsFile")
if ($LASTEXITCODE -ne 0) { throw 'Worker 依赖安装失败' }
Push-Location (Join-Path $taskRoot 'frontend')
try { npm.cmd install; if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' } } finally { Pop-Location }
Write-Output '依赖已就绪。运行 scripts/build.ps1，然后 scripts/start.ps1 -Demo 或 scripts/start.ps1。'
