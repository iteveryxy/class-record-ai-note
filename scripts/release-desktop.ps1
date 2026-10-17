param([switch]$SkipPrepare)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
if(-not $SkipPrepare){& (Join-Path $PSScriptRoot 'prepare-desktop.ps1');if($LASTEXITCODE -ne 0){throw '桌面资源准备失败'}}
Push-Location (Join-Path $taskRoot 'desktop')
try {
    npm.cmd test;if($LASTEXITCODE -ne 0){throw '桌面检查失败'}
    npm.cmd run dist;if($LASTEXITCODE -ne 0){throw '安装包构建失败'}
    node tools/release-manifest.cjs
    if($LASTEXITCODE -ne 0){throw '更新兼容信息生成失败'}
}finally{Pop-Location}
