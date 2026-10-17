param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot

function Get-GitText([string[]]$Arguments) {
    $result = @(& git @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Git failed: git $($Arguments -join ' ')" }
    return ($result -join "`n").Trim()
}

try {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git is not installed or is not on PATH.' }
    Push-Location -LiteralPath $taskRoot
    try {
        $repository = Get-GitText @('rev-parse', '--show-toplevel')
        if ([IO.Path]::GetFullPath($repository).TrimEnd('\', '/') -ne [IO.Path]::GetFullPath($taskRoot).TrimEnd('\', '/')) {
            throw 'This script must belong to the repository root.'
        }

        $branch = Get-GitText @('symbolic-ref', '--quiet', '--short', 'HEAD')
        $changes = Get-GitText @('status', '--porcelain=v1', '--untracked-files=normal')
        if ($changes) {
            & git -c core.quotepath=false status --short
            throw 'There are uncommitted changes. Commit them first, then run push.cmd again.'
        }

        $upstream = Get-GitText @('for-each-ref', '--format=%(upstream)', "refs/heads/$branch")
        if (-not $upstream) { throw 'This branch has no upstream. Link it to a remote branch before running this script.' }
        $remote = Get-GitText @('config', '--get', "branch.$branch.remote")
        $mergeRef = Get-GitText @('config', '--get', "branch.$branch.merge")
        $remotes = @(Get-GitText @('remote')) -split "`r?`n"
        if ($remote -eq '.' -or $remotes -notcontains $remote -or -not $mergeRef.StartsWith('refs/heads/')) {
            throw 'The current branch must track a branch on a configured remote.'
        }
        & git check-ref-format $mergeRef
        if ($LASTEXITCODE -ne 0) { throw 'The upstream branch name is invalid.' }

        $target = $mergeRef.Substring('refs/heads/'.Length)
        Write-Host "Checking $remote/$target for branch $branch ..."
        & git fetch --no-tags -- $remote $mergeRef
        if ($LASTEXITCODE -ne 0) { throw 'Fetch failed. Check your connection and GitHub sign-in, then retry.' }

        $counts = (Get-GitText @('rev-list', '--left-right', '--count', 'HEAD...FETCH_HEAD')) -split '\s+'
        if ($counts.Count -ne 2) { throw 'Could not compare the local and remote branches.' }
        $ahead = [long]$counts[0]
        $behind = [long]$counts[1]
        if ($behind -gt 0) {
            throw "The remote has $behind new commit(s). Update or merge your local branch first. No commits were pushed."
        }
        if ($ahead -eq 0) {
            Write-Host 'Already up to date. Nothing to push.' -ForegroundColor Green
            exit 0
        }

        Write-Host "Pushing $ahead committed change(s) to $remote/$target ..."
        $pushArguments = @('push', '--no-follow-tags')
        if ($DryRun) { $pushArguments += '--dry-run' }
        $pushArguments += @('--', $remote, "HEAD:$mergeRef")
        & git @pushArguments
        if ($LASTEXITCODE -ne 0) { throw 'Push failed. Local commits are preserved. Check the Git message above and retry.' }

        if ($DryRun) { Write-Host 'Dry run passed. The remote branch was not changed.' -ForegroundColor Green }
        else { Write-Host 'Push completed.' -ForegroundColor Green }
    } finally { Pop-Location }
} catch {
    Write-Host "Push stopped: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
