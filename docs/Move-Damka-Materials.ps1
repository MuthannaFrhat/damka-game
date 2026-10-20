# Run this downloaded script BEFORE pulling the commit that removes the materials.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$SnapshotCommit,
    [string]$ProjectPath = (Get-Location).Path,
    [string]$DestinationParent
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
$script:Repo = (Resolve-Path -LiteralPath $ProjectPath).ProviderPath
$script:Bundle = $null

function Run-Git {
    param([string[]]$GitArgs)
    $result = @(& git -C $script:Repo @GitArgs)
    if ($LASTEXITCODE -ne 0) {
        throw "Git failed (exit $LASTEXITCODE): git $($GitArgs -join ' ')"
    }
    return $result
}

function Assert-NoReparseAncestors {
    param([string]$Path)
    $probe = [IO.Path]::GetFullPath($Path)
    while ($probe) {
        if (Test-Path -LiteralPath $probe) {
            $item = Get-Item -LiteralPath $probe -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "A link or junction is not supported: $probe"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($probe)
        if ($parent -eq $probe) { break }
        $probe = $parent
    }
}

function Get-SafeEntries {
    param([string]$Root)
    Assert-NoReparseAncestors $Root
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($Root)
    while ($pending.Count -gt 0) {
        $path = $pending.Pop()
        $entry = Get-Item -LiteralPath $path -Force
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "A link or junction is not supported: $path"
        }
        $entry
        if ($entry.PSIsContainer) {
            foreach ($child in @(Get-ChildItem -LiteralPath $path -Force)) {
                if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw "A link or junction is not supported: $($child.FullName)"
                }
                $pending.Push($child.FullName)
            }
        }
    }
}

function Get-Manifest {
    param([string]$Root)
    $rootItem = Get-Item -LiteralPath $Root -Force
    $manifest = @{}
    foreach ($entry in @(Get-SafeEntries $rootItem.FullName)) {
        $relative = $entry.FullName.Substring($rootItem.FullName.Length).TrimStart([char[]]'\/')
        if ($entry.PSIsContainer) { $manifest[$relative] = '<DIRECTORY>' }
        else { $manifest[$relative] = (Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash }
    }
    return $manifest
}

function Assert-ManifestsEqual {
    param([hashtable]$Expected, [hashtable]$Actual, [string]$Label)
    if ($Expected.Count -ne $Actual.Count) { throw "Files changed during backup: $Label" }
    foreach ($key in $Expected.Keys) {
        if (!$Actual.ContainsKey($key) -or $Expected[$key] -ne $Actual[$key]) {
            throw "Backup verification failed: $Label ($key)"
        }
    }
}

function Copy-VerifiedItem {
    param([string]$Source, [string]$Destination)
    $manifest = Get-Manifest $Source
    foreach ($relative in $manifest.Keys) {
        $sourceFile = $Source
        $targetFile = $Destination
        if ($relative) {
            $sourceFile = Join-Path $Source $relative
            $targetFile = Join-Path $Destination $relative
        }
        if ($manifest[$relative] -eq '<DIRECTORY>') {
            New-Item -ItemType Directory -Path $targetFile -Force | Out-Null
        } else {
            Assert-NoReparseAncestors $sourceFile
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($targetFile)) -Force | Out-Null
            Copy-Item -LiteralPath $sourceFile -Destination $targetFile -Force
        }
    }
    Assert-ManifestsEqual $manifest (Get-Manifest $Source) $Source
    Assert-ManifestsEqual $manifest (Get-Manifest $Destination) $Destination
    return $manifest
}

try {
    $root = @(Run-Git -GitArgs @('rev-parse', '--show-toplevel'))[0]
    $script:Repo = [IO.Path]::GetFullPath($root)
    Assert-NoReparseAncestors $script:Repo
    $branch = @(Run-Git -GitArgs @('symbolic-ref', '--quiet', '--short', 'HEAD'))[0]
    if ($branch -ne 'main') { throw 'Switch to your local main branch before running this script.' }
    foreach ($state in @('MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply')) {
        $statePath = @(Run-Git -GitArgs @('rev-parse', '--git-path', $state))[0]
        if (![IO.Path]::IsPathRooted($statePath)) { $statePath = Join-Path $script:Repo $statePath }
        if (Test-Path -LiteralPath $statePath) { throw 'Finish the current Git operation before running this script.' }
    }
    $scopes = @('docs', 'LEARNING.md', 'learning.md')
    if (@(Run-Git -GitArgs @('diff', '--name-only', '--diff-filter=U')).Count -gt 0) {
        throw 'Resolve existing Git conflicts before running this script.'
    }
    Write-Host 'Fetching the cleanup commit. Your working files have not been changed.'
    Run-Git -GitArgs @('fetch', 'origin', 'refs/heads/main:refs/remotes/origin/main') | Out-Null
    $cleanup = @(Run-Git -GitArgs @('rev-parse', 'refs/remotes/origin/main'))[0]
    Run-Git -GitArgs @('cat-file', '-e', "$SnapshotCommit^{commit}") | Out-Null
    Run-Git -GitArgs @('merge-base', '--is-ancestor', 'HEAD', $cleanup) | Out-Null
    Run-Git -GitArgs @('merge-base', '--is-ancestor', $SnapshotCommit, $cleanup) | Out-Null
    if (@(Run-Git -GitArgs (@('ls-tree', '-r', '--name-only', $cleanup, '--') + $scopes)).Count -gt 0) {
        throw 'The fetched main branch still tracks materials. Wait for the cleanup commit.'
    }
    if (!$DestinationParent) {
        $DestinationParent = Join-Path ([IO.Path]::GetDirectoryName($script:Repo)) 'damka-materials'
    }
    $DestinationParent = [IO.Path]::GetFullPath($DestinationParent)
    $repoPrefix = $script:Repo.TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
    if ($DestinationParent.Equals($script:Repo, [StringComparison]::OrdinalIgnoreCase) -or
        $DestinationParent.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The materials destination must be outside the Git repository.'
    }
    Assert-NoReparseAncestors $DestinationParent
    $bundleName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $script:Bundle = Join-Path $DestinationParent $bundleName
    New-Item -ItemType Directory -Path $script:Bundle -Force | Out-Null
    $updated = Join-Path $script:Bundle 'Updated-materials'
    $existing = Join-Path $script:Bundle 'Your-existing-files'
    $archive = Join-Path $script:Bundle 'Materials-snapshot.zip'
    $archiveScopes = @(Run-Git -GitArgs (@('ls-tree', '--name-only', $SnapshotCommit, '--') + $scopes))
    if ($archiveScopes -notcontains 'docs') { throw 'The supplied snapshot does not contain the materials.' }
    Write-Host "Saving the latest materials and your existing files to $($script:Bundle)"
    Run-Git -GitArgs (@('archive', '--format=zip', "--output=$archive", $SnapshotCommit, '--') + $archiveScopes) | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $updated
    $updatedManifest = Get-Manifest $updated
    $updatedManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $script:Bundle 'Updated-materials.sha256.json') -Encoding UTF8
    $checks = New-Object 'System.Collections.Generic.List[object]'
    foreach ($scope in @('docs', 'demo/output', 'LEARNING.md', 'learning.md')) {
        $source = Join-Path $script:Repo $scope
        if (!(Test-Path -LiteralPath $source)) { continue }
        if (@($checks | Where-Object { $_.Source -eq $source }).Count -gt 0) { continue }
        $destination = Join-Path $existing $scope
        $manifest = Copy-VerifiedItem $source $destination
        $checks.Add([pscustomobject]@{ Source = $source; Backup = $destination; SHA256 = $manifest })
    }
    $checks.ToArray() | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $script:Bundle 'Your-existing-files.sha256.json') -Encoding UTF8
    Run-Git -GitArgs (@('diff', '--binary', "--output=$(Join-Path $script:Bundle 'Unstaged-materials.patch')", '--') + $scopes) | Out-Null
    Run-Git -GitArgs (@('diff', '--cached', '--binary', "--output=$(Join-Path $script:Bundle 'Staged-materials.patch')", '--') + $scopes) | Out-Null
    @("Repository: $($script:Repo)", "Snapshot: $SnapshotCommit", "Cleanup: $cleanup",
        'Updated-materials contains the latest diagrams and documents.',
        'Your-existing-files preserves your PC versions separately, including local recordings.',
        'The patch files also preserve staged and unstaged material edits.') |
        Set-Content -LiteralPath (Join-Path $script:Bundle 'READ-ME.txt') -Encoding UTF8
    foreach ($check in $checks) {
        Assert-ManifestsEqual $check.SHA256 (Get-Manifest $check.Source) $check.Source
        Assert-ManifestsEqual $check.SHA256 (Get-Manifest $check.Backup) $check.Backup
    }
    Assert-ManifestsEqual $updatedManifest (Get-Manifest $updated) $updated
    $tracked = @(Run-Git -GitArgs (@('-c', 'core.quotepath=false', 'ls-files', '--') + $scopes))
    Write-Host 'Backups verified. Applying the cleanup while keeping application changes untouched.'
    if ($tracked.Count -gt 0) {
        Run-Git -GitArgs (@('restore', '--source=HEAD', '--staged', '--worktree', '--') + $tracked) | Out-Null
    }
    Run-Git -GitArgs @('merge', '--ff-only', $cleanup) | Out-Null
    Write-Host "Finished. Your materials are in: $($script:Bundle)"
    Write-Host 'Open Updated-materials for the latest documents; Your-existing-files holds your previous PC copies.'
    if ($env:OS -eq 'Windows_NT') {
        try { Start-Process -FilePath explorer.exe -ArgumentList ('"' + $script:Bundle + '"') | Out-Null }
        catch { Write-Warning 'Could not open Explorer. Open the folder shown above.' }
    }
} catch {
    if ($script:Bundle) { Write-Host "Any completed backups remain at: $($script:Bundle)" }
    throw
}
