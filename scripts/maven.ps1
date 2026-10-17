$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$repoPath = Join-Path $taskRoot '.tools/m2'
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$localMaven = Join-Path $taskRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if ($mavenCommand) { $mavenPath = $mavenCommand.Source }
elseif (Test-Path -LiteralPath $localMaven) { $mavenPath = $localMaven }
else { throw '请先运行 scripts/setup.ps1，或将 Maven 加入 PATH。' }
& $mavenPath "-Dmaven.repo.local=$repoPath" '-f' (Join-Path $taskRoot 'backend/pom.xml') @args
exit $LASTEXITCODE
