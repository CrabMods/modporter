param(
    [string]$Cases = '',
    [string]$RunId = ('strict-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$CreateSource = '',
    [switch]$KeepWork
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if ($RunId -notmatch '^[a-z0-9][a-z0-9._-]{0,63}$') { throw 'Invalid unique run ID' }
if (Test-Path -LiteralPath (Join-Path $taskRoot "build/real-mod-benchmark/runs/$RunId")) {
    throw 'The run ID already exists; choose a new one'
}
$taskAvailable = @(Get-Content (Join-Path $taskRoot 'src/test/resources/benchmarks/real-mods.tsv') |
    Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith('#') } |
    ForEach-Object { ,($_ -split "`t") } |
    Where-Object { $_.Count -ge 7 -and $_[2] -ne 'missing' } |
    ForEach-Object { $_[0] })
$taskSelected = if ($Cases) { @($Cases -split ',') } else { $taskAvailable }
if (@($taskSelected | Where-Object { $_ -notin $taskAvailable }).Count -ne 0) {
    throw 'A requested case is not an available benchmark target'
}
if ('worldedit' -in $taskSelected -and -not $env:MODPORTER_BENCHMARK_SOURCE_WORLDEDIT) {
    $taskPreparation = & python (Join-Path $PSScriptRoot 'prepare_worldedit_benchmark.py') --json-only
    if ($LASTEXITCODE -ne 0) { throw 'WorldEdit source preparation failed' }
    $taskPrepared = ($taskPreparation -join "`n") | ConvertFrom-Json
    $env:MODPORTER_BENCHMARK_SOURCE_WORLDEDIT = $taskPrepared.sourceOverride.value
    Write-Host 'WorldEdit: verified Forge-only input prepared from the pinned official revision.'
}
if ($CreateSource) {
    $env:MODPORTER_BENCHMARK_SOURCE_CREATE = (Resolve-Path -LiteralPath $CreateSource).Path
}
$env:MODPORTER_BENCHMARK_RUN_ID = $RunId
$env:MODPORTER_BENCHMARK_CASES = $taskSelected -join ','
$env:MODPORTER_BENCHMARK_STRICT_RUNTIME = 'true'
$env:MODPORTER_BENCHMARK_KEEP_WORK = $KeepWork.IsPresent.ToString().ToLowerInvariant()
$env:MODPORTER_BENCHMARK_UPDATE_README = 'false'
& (Join-Path $taskRoot 'mod-build.ps1') gradle strictRealModBenchmark --no-daemon
exit $LASTEXITCODE
