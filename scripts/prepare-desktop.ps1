param([switch]$SkipBuild, [switch]$SkipWorker)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$resources=Join-Path $taskRoot 'desktop/resources'
$desktopVersion=(Get-Content -LiteralPath (Join-Path $taskRoot 'desktop/package.json') -Raw|ConvertFrom-Json).version
$backendPom=[xml](Get-Content -LiteralPath (Join-Path $taskRoot 'backend/pom.xml') -Raw)
if($desktopVersion -ne $backendPom.project.version){throw '后端与桌面版的发布版本不一致，请先统一版本号'}
New-Item -ItemType Directory -Force -Path $resources | Out-Null
if(-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1');if($LASTEXITCODE -ne 0){throw '项目检查失败'} }
if(-not $SkipWorker) {
    Push-Location (Join-Path $taskRoot 'worker')
    try { & '.\.venv\Scripts\python.exe' -m PyInstaller --noconfirm build-desktop.spec;if($LASTEXITCODE -ne 0){throw 'ASR Worker 打包失败'} }finally{Pop-Location}
}
$javaCommand=(Get-Command java.exe).Source
$javaHome=Split-Path -Parent (Split-Path -Parent $javaCommand)
$jlink=Join-Path $javaHome 'bin/jlink.exe'
$runtime=Join-Path $resources 'java'
if(-not(Test-Path -LiteralPath (Join-Path $runtime 'bin/java.exe'))) {
    & $jlink --add-modules 'java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.security.jgss,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.management,jdk.unsupported,jdk.zipfs,jdk.security.auth' --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
    if($LASTEXITCODE -ne 0){throw 'Java 运行时裁剪失败'}
}
foreach($folder in @('backend','ui','worker')){New-Item -ItemType Directory -Force -Path (Join-Path $resources $folder)|Out-Null}
Copy-Item -LiteralPath (Join-Path $taskRoot "backend/target/class-notes-$desktopVersion.jar") -Destination (Join-Path $resources 'backend/class-notes.jar') -Force
Copy-Item -Path (Join-Path $taskRoot 'frontend/dist/*') -Destination (Join-Path $resources 'ui') -Recurse -Force
Copy-Item -Path (Join-Path $taskRoot 'worker/dist/ClassNotesWorker/*') -Destination (Join-Path $resources 'worker') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'desktop/build/icon.png') -Destination (Join-Path $resources 'icon.png') -Force
$releaseConfig=Join-Path $taskRoot 'desktop/build/release.json'
if(Test-Path -LiteralPath $releaseConfig){Copy-Item -LiteralPath $releaseConfig -Destination (Join-Path $resources 'release.json') -Force}
Write-Output '桌面资源已准备，可在 desktop 目录运行 npm run dist。'
