param([string]$PreviousInstaller, [string]$FixtureDataDirectory, [ValidateSet('system','zh-CN','en')][string]$Language)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$desktopVersion=(Get-Content -LiteralPath (Join-Path $taskRoot 'desktop/package.json') -Raw|ConvertFrom-Json).version
$installer=Join-Path $taskRoot "artifacts/desktop/ClassNotes-Setup-$desktopVersion-x64.exe"
$existing=@(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object {$_.DisplayName -eq '课记'})
if($existing.Count){throw '已有课记安装，跳过自动安装测试以保留现有程序。'}
$testRoot=Join-Path $taskRoot ('.tools/installer-smoke-'+[guid]::NewGuid().ToString('N'))
$installDir=[IO.Path]::GetFullPath((Join-Path $testRoot 'installed'))
$userDir=[IO.Path]::GetFullPath((Join-Path $testRoot 'user-data'))
foreach($target in @($installDir,$userDir)) {if(-not $target.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '测试目标超出项目目录'}}
New-Item -ItemType Directory -Path $testRoot|Out-Null
if($FixtureDataDirectory) {
    $fixture=[IO.Path]::GetFullPath($FixtureDataDirectory)
    $fixtureRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot '.tools'))+[IO.Path]::DirectorySeparatorChar
    if(-not $fixture.StartsWith($fixtureRoot,[StringComparison]::OrdinalIgnoreCase)){throw '兼容性测试仅接受项目内已关闭的测试资料'}
    if(-not(Test-Path -LiteralPath (Join-Path $fixture 'classnotes.db'))){throw '兼容性测试资料缺少数据库'}
    New-Item -ItemType Directory -Force -Path $userDir|Out-Null
    Copy-Item -LiteralPath $fixture -Destination (Join-Path $userDir 'data') -Recurse
}
$preferences=Join-Path $userDir 'desktop.json'
if($Language) {
    New-Item -ItemType Directory -Force -Path $userDir|Out-Null
    @{language=$Language;updateUrl=''}|ConvertTo-Json|Set-Content -LiteralPath $preferences -Encoding utf8NoBOM
}
$python=Join-Path $taskRoot 'worker/.venv/Scripts/python.exe'
if(-not(Test-Path -LiteralPath $python)){throw '安装测试需要已建立的 Worker 开发环境，用于比较测试资料'}
$snapshot=Join-Path $testRoot 'data-before.json'
function Check-TestData([string]$action) {
    & $python (Join-Path $PSScriptRoot 'installer-data-check.py') $action (Join-Path $userDir 'data') $snapshot
    if($LASTEXITCODE -ne 0){throw '安装兼容性资料检查失败'}
}
function Install-TestVersion([string]$source=$installer) {
    $process=Start-Process -FilePath $source -ArgumentList "/S /D=$installDir" -WindowStyle Hidden -PassThru -Wait
    if($process.ExitCode -ne 0){throw "安装失败：$($process.ExitCode)"}
    if(-not(Test-Path -LiteralPath (Join-Path $installDir 'ClassNotes.exe'))){throw '安装后的主程序不存在'}
}
function Run-Diagnostic([string]$name) {
    $output=Join-Path $taskRoot "artifacts/runtime/$name-$desktopVersion.json"
    $arguments=@('--demo',"--data-dir=$userDir","--diagnostics=$output")
    $process=Start-Process -FilePath (Join-Path $installDir 'ClassNotes.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru -Wait
    if($process.ExitCode -ne 0){throw '安装后的桌面诊断退出异常'}
    $result=Get-Content -LiteralPath $output -Raw|ConvertFrom-Json
    if(-not $result.ok){throw '安装后的桌面窗口或本地服务未就绪'}
    return $result
}
if($PreviousInstaller){Install-TestVersion ([IO.Path]::GetFullPath($PreviousInstaller))}else{Install-TestVersion}
$before=Run-Diagnostic 'installed-before-overlay'
Check-TestData 'capture'
$preferencesBefore=if($Language){(Get-FileHash -LiteralPath $preferences -Algorithm SHA256).Hash}else{''}
$keyBefore=(Get-FileHash -LiteralPath (Join-Path $userDir 'data/settings.key') -Algorithm SHA256).Hash
$marker=Join-Path $userDir 'retention-marker.txt'
Set-Content -LiteralPath $marker -Value 'upgrade-and-uninstall-must-retain-this-data' -Encoding utf8
Install-TestVersion
$after=Run-Diagnostic 'installed-after-overlay'
if($after.version -ne $desktopVersion){throw '覆盖更新后仍在运行旧版本'}
if($Language) {
    if((Get-FileHash -LiteralPath $preferences -Algorithm SHA256).Hash -ne $preferencesBefore){throw '升级改变了语言偏好'}
    if($Language -eq 'en' -and -not $after.renderer.body.Contains('My lecture recordings')){throw '升级后未使用保存的英文界面'}
    if($Language -eq 'zh-CN' -and -not $after.renderer.body.Contains('我的课堂录音')){throw '升级后未使用保存的中文界面'}
}
Check-TestData 'verify'
$keyAfter=(Get-FileHash -LiteralPath (Join-Path $userDir 'data/settings.key') -Algorithm SHA256).Hash
if($keyBefore -ne $keyAfter -or -not(Test-Path -LiteralPath $marker)){throw '覆盖安装丢失了测试数据或凭证密钥'}
if(@(Get-ChildItem -LiteralPath $testRoot -Directory | Where-Object {$_.Name -match '^ns.*\.tmp$'}).Count){throw '覆盖安装未清理旧程序暂存目录'}
$uninstaller=@(Get-ChildItem -LiteralPath $installDir -File|Where-Object {$_.Name -like '*Uninstall*.exe'})
if($uninstaller.Count -ne 1){throw '无法确定唯一卸载程序'}
$uninstallPath=[IO.Path]::GetFullPath($uninstaller[0].FullName)
if(-not $uninstallPath.StartsWith($installDir+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '卸载目标超出测试安装目录'}
$process=Start-Process -FilePath $uninstallPath -ArgumentList '/currentuser /S' -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -Wait
if($process.ExitCode -ne 0){throw '测试卸载失败'}
if(Test-Path -LiteralPath (Join-Path $installDir 'ClassNotes.exe')){throw '卸载后主程序仍然存在'}
if((Test-Path -LiteralPath $installDir) -and @(Get-ChildItem -LiteralPath $installDir -Recurse -File).Count){throw '卸载后仍有程序文件残留'}
if(-not(Test-Path -LiteralPath (Join-Path $userDir 'data/classnotes.db')) -or -not(Test-Path -LiteralPath $marker)){throw '卸载删除了用户数据'}
Check-TestData 'verify'
$remaining=@(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue|Where-Object {$_.DisplayName -eq '课记'})
if($remaining.Count){throw '测试卸载后注册表记录仍然存在'}
if($Language -and (Get-FileHash -LiteralPath $preferences -Algorithm SHA256).Hash -ne $preferencesBefore){throw '卸载改变了语言偏好'}
@{installed=$true;overlayPreservedKey=$true;overlayPreservedDatabaseRows=$true;uninstalled=$true;dataRetained=$true;languagePreference=$Language;languagePreferenceRetained=[bool]$Language;testRoot=$testRoot;previousVersion=$before.version;version=$after.version}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/runtime/installer-smoke-$desktopVersion-result.json") -Encoding utf8
Write-Output '安装、覆盖重装、卸载与数据保留检查通过。'
