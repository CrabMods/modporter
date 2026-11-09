$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = @(Get-Command python -CommandType Application -ErrorAction Stop)[0].Source
$BuildLauncher = Join-Path $ProjectRoot 'scripts/run_gradle.py'
& $Python -B $BuildLauncher --project-root $ProjectRoot @args
exit $LASTEXITCODE
