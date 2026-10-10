param([string]$Destination)
$ErrorActionPreference = 'Stop'
if (-not $Destination) {
  $taskProfile = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE '.codex' }
  $Destination = Join-Path $taskProfile 'skills'
}
$taskPackage = $PSScriptRoot
$taskManifest = Get-Content -LiteralPath (Join-Path $taskPackage 'manifest.json') -Raw | ConvertFrom-Json
$taskGroups = @('skills', 'reused-skills')
$taskInstall = @()
foreach ($row in $taskManifest) {
  if ($row.path.StartsWith('/') -or $row.path.Contains('..') -or $row.path.Contains('\')) { throw 'Unsafe package path' }
  $taskFile = Join-Path $taskPackage $row.path
  if ((Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row.sha256) { throw ('Package hash mismatch: ' + $row.path) }
}
foreach ($group in $taskGroups) {
  Get-ChildItem -LiteralPath (Join-Path $taskPackage $group) -Directory | ForEach-Object {
    $source = $_.FullName
    $target = Join-Path $Destination $_.Name
    $prefix = $group + '/' + $_.Name + '/'
    $rows = @($taskManifest | Where-Object { $_.path.StartsWith($prefix) })
    if (Test-Path -LiteralPath $target) {
      $same = $true
      foreach ($row in $rows) {
        $existing = Join-Path $target $row.path.Substring($prefix.Length)
        if (-not (Test-Path -LiteralPath $existing -PathType Leaf) -or (Get-FileHash -LiteralPath $existing -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row.sha256) { $same = $false }
      }
      if ($same) { Write-Output ('Already installed and verified: ' + $_.Name) }
      else { throw ('Existing skill differs; review and preserve it before installing: ' + $_.Name) }
    } else { $taskInstall += [pscustomobject]@{ source=$source; target=$target; name=$_.Name; rows=$rows; prefix=$prefix } }
  }
}
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
foreach ($item in $taskInstall) {
  Copy-Item -LiteralPath $item.source -Destination $item.target -Recurse
  foreach ($row in $item.rows) {
    $file = Join-Path $item.target $row.path.Substring($item.prefix.Length)
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row.sha256) { throw ('Installed hash mismatch: ' + $item.name) }
  }
  Write-Output ('Installed and verified: ' + $item.name)
}
Write-Output ('Completed. Installed ' + $taskInstall.Count + ' skills. Restart Codex if discovery has not refreshed.')
