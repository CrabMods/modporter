[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ProjectRoot,
    [Parameter(Mandatory)][string]$RuntimeDirectory,
    [Parameter(Mandatory)][string]$Executable,
    [string[]]$ArgumentList = @(),
    [ValidateSet('client', 'gametest-server', 'server', 'other')]
    [string]$RuntimeKind = 'client',
    [ValidateRange(0, 86400)][int]$LockTimeoutSeconds = 0,
    [ValidateRange(0, 86400)][int]$TimeoutSeconds = 7200,
    [string]$ReceiptPath = '',
    [string]$HarnessReceiptPath = '',
    [int]$ExpectedScenarioCount = -1,
    [string[]]$ExpectedScenarioIds = @(),
    [string]$ExpectedScenarioIdsCsv = '',
    [int]$ExpectedProcessStartCount = 1,
    [int]$ExpectedWorldLoadCount = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime_process_inventory.ps1')

function Resolve-SessionPath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$PathValue
    )
    if ([IO.Path]::IsPathRooted($PathValue)) {
        return [IO.Path]::GetFullPath($PathValue)
    }
    return [IO.Path]::GetFullPath((Join-Path $Root $PathValue))
}

function Write-JsonImmutable {
    param(
        [Parameter(Mandatory)][string]$PathValue,
        [Parameter(Mandatory)][object]$Value
    )
    if (Test-Path -LiteralPath $PathValue) {
        throw "Immutable session evidence already exists: $PathValue"
    }
    $parent = Split-Path -Parent $PathValue
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temporary = "$PathValue.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Value | ConvertTo-Json -Depth 40 |
            Set-Content -LiteralPath $temporary -Encoding utf8
        [IO.File]::Move($temporary, $PathValue, $false)
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Restore-EnvironmentValue {
    param(
        [Parameter(Mandatory)][string]$Name,
        [AllowNull()][string]$Value
    )
    if ($null -eq $Value) {
        Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue
    } else {
        Set-Item -LiteralPath "Env:$Name" -Value $Value
    }
}

function Get-ModporterPathComparison {
    if ($IsWindows) {
        return [StringComparison]::OrdinalIgnoreCase
    }
    return [StringComparison]::Ordinal
}

function Get-ModporterCanonicalPathText {
    param([Parameter(Mandatory)][string]$PathValue)

    $fullPath = [IO.Path]::GetFullPath($PathValue)
    $root = [IO.Path]::GetPathRoot($fullPath)
    while ($fullPath.Length -gt $root.Length -and
            ($fullPath.EndsWith([IO.Path]::DirectorySeparatorChar) -or
                $fullPath.EndsWith([IO.Path]::AltDirectorySeparatorChar))) {
        $fullPath = $fullPath.Substring(0, $fullPath.Length - 1)
    }
    return $fullPath
}

function Get-ModporterRuntimeResourceText {
    param([Parameter(Mandatory)][string]$CanonicalPath)

    if ($IsWindows) {
        return $CanonicalPath.ToLowerInvariant()
    }
    return $CanonicalPath
}

function Test-JsonBooleanTrue {
    param([AllowNull()][object]$Value)
    return $Value -is [bool] -and [bool]$Value
}

function Get-ArtifactDescriptorErrors {
    param(
        [Parameter(Mandatory)][object]$Artifact,
        [Parameter(Mandatory)][string]$Label
    )

    $errors = [Collections.Generic.List[string]]::new()
    $pathProperty = $Artifact.PSObject.Properties['path']
    $shaProperty = $Artifact.PSObject.Properties['sha256']
    $bytesProperty = $Artifact.PSObject.Properties['bytes']
    if ($null -eq $pathProperty -or $null -eq $shaProperty -or
            $null -eq $bytesProperty) {
        $errors.Add("$Label must contain path, sha256, and bytes")
        return $errors
    }

    $artifactPath = [string]$pathProperty.Value
    $expectedSha256 = [string]$shaProperty.Value
    $expectedBytes = $bytesProperty.Value
    if ([string]::IsNullOrWhiteSpace($artifactPath) -or
            -not [IO.Path]::IsPathRooted($artifactPath)) {
        $errors.Add("$Label.path must be an absolute path")
        return $errors
    }
    if ($expectedSha256 -cnotmatch '^[0-9a-f]{64}$') {
        $errors.Add("$Label.sha256 must be 64 lowercase hexadecimal characters")
        return $errors
    }
    if ($expectedBytes -isnot [int] -and $expectedBytes -isnot [long]) {
        $errors.Add("$Label.bytes must be an integer")
        return $errors
    }
    if ([long]$expectedBytes -lt 0) {
        $errors.Add("$Label.bytes must be nonnegative")
        return $errors
    }

    try {
        $item = Get-Item -LiteralPath $artifactPath -Force -ErrorAction Stop
        if ($item.PSIsContainer) {
            throw 'path is not a regular file'
        }
        $observedBytes = [long]$item.Length
        $observedSha256 = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath $item.FullName).Hash.ToLowerInvariant()
        if ($observedBytes -ne [long]$expectedBytes) {
            $errors.Add("$Label.bytes does not match the referenced file")
        }
        if ($observedSha256 -cne $expectedSha256) {
            $errors.Add("$Label.sha256 does not match the referenced file")
        }
    } catch {
        $errors.Add("$Label cannot be verified: $($_.Exception.Message)")
    }
    return $errors
}

function Get-EmbeddedArtifactErrors {
    param(
        [AllowNull()][object]$Value,
        [Parameter(Mandatory)][string]$Label
    )

    $errors = [Collections.Generic.List[string]]::new()
    if ($null -eq $Value -or $Value -is [string] -or
            $Value -is [ValueType]) {
        return $errors
    }
    if ($Value -is [Management.Automation.PSCustomObject]) {
        $properties = $Value.PSObject.Properties
        if ($null -ne $properties['path'] -and
                $null -ne $properties['sha256'] -and
                $null -ne $properties['bytes']) {
            foreach ($errorText in @(Get-ArtifactDescriptorErrors `
                    -Artifact $Value -Label $Label)) {
                $errors.Add($errorText)
            }
            return $errors
        }
        foreach ($property in $properties) {
            foreach ($errorText in @(Get-EmbeddedArtifactErrors `
                    -Value $property.Value `
                    -Label "$Label.$($property.Name)")) {
                $errors.Add($errorText)
            }
        }
        return $errors
    }
    if ($Value -is [Collections.IEnumerable]) {
        $index = 0
        foreach ($entry in $Value) {
            foreach ($errorText in @(Get-EmbeddedArtifactErrors `
                    -Value $entry -Label "$Label[$index]")) {
                $errors.Add($errorText)
            }
            $index++
        }
    }
    return $errors
}

function Get-OwnedProcessIds {
    param(
        [Parameter(Mandatory)][int]$RootProcessId,
        [Parameter(Mandatory)][datetime]$RootStartedAtUtc
    )
    $inventory = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $owned = [Collections.Generic.HashSet[int]]::new()
    $null = $owned.Add($RootProcessId)
    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($entry in $inventory) {
            $processId = [int]$entry.ProcessId
            if ($owned.Contains($processId) -or
                    -not $owned.Contains([int]$entry.ParentProcessId)) {
                continue
            }
            $created = if ($null -eq $entry.CreationDate) {
                $null
            } elseif ($entry.CreationDate -is [datetime]) {
                ([datetime]$entry.CreationDate).ToUniversalTime()
            } else {
                [Management.ManagementDateTimeConverter]::ToDateTime(
                    [string]$entry.CreationDate).ToUniversalTime()
            }
            if ($null -ne $created -and
                    $created -lt $RootStartedAtUtc.AddSeconds(-2)) {
                continue
            }
            $null = $owned.Add($processId)
            $changed = $true
        }
    }
    return @($owned | Sort-Object)
}

function Invoke-OwnedCommand {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][int]$CommandTimeoutSeconds
    )

    $launchFilePath = $FilePath
    $launchArguments = @($Arguments)
    if ([IO.Path]::GetExtension($FilePath) -ieq '.ps1') {
        $launchFilePath = (Get-Command pwsh -CommandType Application `
            -ErrorAction Stop | Select-Object -First 1).Source
        $launchArguments = @('-NoProfile', '-File', $FilePath) +
            @($Arguments)
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $launchFilePath
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $launchArguments) {
        $null = $startInfo.ArgumentList.Add([string]$argument)
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $startedAtUtc = [DateTime]::UtcNow
    $timedOut = $false
    $killedProcessIds = @()
    try {
        if (-not $process.Start()) {
            throw "Failed to start runtime command: $FilePath"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $finished = if ($CommandTimeoutSeconds -eq 0) {
            $process.WaitForExit()
            $true
        } else {
            $process.WaitForExit($CommandTimeoutSeconds * 1000)
        }
        if (-not $finished) {
            $timedOut = $true
            try {
                $killedProcessIds = @(Get-OwnedProcessIds `
                    -RootProcessId $process.Id `
                    -RootStartedAtUtc $startedAtUtc)
            } catch {
                $killedProcessIds = @($process.Id)
            }
            try {
                $process.Kill($true)
            } catch [InvalidOperationException] {
            }
            if (-not $process.WaitForExit(30000)) {
                throw "Owned runtime process tree did not exit after timeout: $($process.Id)"
            }
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        return [pscustomobject][ordered]@{
            processId = $process.Id
            startedAtUtc = $startedAtUtc
            finishedAtUtc = [DateTime]::UtcNow
            exitCode = if ($timedOut) { 124 } else { $process.ExitCode }
            timedOut = $timedOut
            killedProcessIds = @($killedProcessIds)
            stdout = $stdout
            stderr = $stderr
        }
    } finally {
        $process.Dispose()
    }
}

function Test-StringSequenceEqual {
    param([object[]]$Left, [object[]]$Right)
    $leftValues = @($Left)
    $rightValues = @($Right)
    if ($leftValues.Count -ne $rightValues.Count) {
        return $false
    }
    for ($index = 0; $index -lt $leftValues.Count; $index++) {
        if ([string]$leftValues[$index] -cne
                [string]$rightValues[$index]) {
            return $false
        }
    }
    return $true
}

function Get-HarnessErrors {
    param(
        [Parameter(Mandatory)][object]$Harness,
        [Parameter(Mandatory)][string]$SessionId,
        [Parameter(Mandatory)][string]$CanonicalRuntimeDirectory,
        [Parameter(Mandatory)][datetime]$SessionStartedAtUtc
    )

    $errors = [Collections.Generic.List[string]]::new()
    if ([string]$Harness.sessionId -cne $SessionId) {
        $errors.Add('sessionId mismatch')
    }
    if ([int]$Harness.processStartCount -ne $ExpectedProcessStartCount) {
        $errors.Add("processStartCount must be $ExpectedProcessStartCount")
    }
    if ([int]$Harness.worldLoadCount -ne $ExpectedWorldLoadCount) {
        $errors.Add("worldLoadCount must be $ExpectedWorldLoadCount")
    }
    if ([int]$Harness.failedCount -ne 0) {
        $errors.Add('failedCount must be 0')
    }
    if ([int]$Harness.cleanupFailureCount -ne 0) {
        $errors.Add('cleanupFailureCount must be 0')
    }
    if (-not (Test-JsonBooleanTrue $Harness.allScenariosPassed)) {
        $errors.Add('allScenariosPassed must be the JSON boolean true')
    }

    $requested = @($Harness.requestedScenarios | ForEach-Object {
        [string]$_
    })
    $observed = @($Harness.observedScenarios | ForEach-Object {
        [string]$_
    })
    $scenarios = @($Harness.scenarios)
    if (-not (Test-StringSequenceEqual -Left $requested -Right $observed)) {
        $errors.Add('requestedScenarios and observedScenarios must match exactly in order and multiplicity')
    }
    if ([int]$Harness.scenarioCount -ne $requested.Count) {
        $errors.Add('scenarioCount must equal requestedScenarios count')
    }
    if ($ExpectedScenarioCount -ge 0 -and
            [int]$Harness.scenarioCount -ne $ExpectedScenarioCount) {
        $errors.Add("scenarioCount must be $ExpectedScenarioCount")
    }
    if ($effectiveExpectedScenarioIds.Count -gt 0 -and
            -not (Test-StringSequenceEqual -Left $effectiveExpectedScenarioIds -Right $requested)) {
        $errors.Add('requestedScenarios do not match ExpectedScenarioIds')
    }
    if ($scenarios.Count -ne $requested.Count) {
        $errors.Add('scenarios must contain exactly one row per requested scenario')
    } else {
        for ($index = 0; $index -lt $scenarios.Count; $index++) {
            $scenario = $scenarios[$index]
            if ([string]$scenario.id -cne $requested[$index]) {
                $errors.Add("scenario[$index].id does not match requested order")
            }
            if (-not (Test-JsonBooleanTrue $scenario.passed)) {
                $errors.Add("scenario[$index].passed must be the JSON boolean true")
            }
            if (-not (Test-JsonBooleanTrue $scenario.cleanupPassed)) {
                $errors.Add("scenario[$index].cleanupPassed must be the JSON boolean true")
            }
            if ($null -ne $scenario.PSObject.Properties['unexecuted'] -and
                    ($scenario.unexecuted -isnot [bool] -or
                        [bool]$scenario.unexecuted)) {
                $errors.Add("scenario[$index].unexecuted must be the JSON boolean false")
            }
            if ([long]$scenario.startedAtTick -lt 0 -or
                    [long]$scenario.finishedAtTick -lt
                        [long]$scenario.startedAtTick) {
                $errors.Add("scenario[$index] has invalid tick bounds")
            }
            if ($null -eq $scenario.PSObject.Properties['artifacts']) {
                $errors.Add("scenario[$index].artifacts is required")
            }
        }
    }
    if ($null -eq $Harness.PSObject.Properties['sealedAtUtc'] -or
            [string]::IsNullOrWhiteSpace([string]$Harness.sealedAtUtc)) {
        $errors.Add('sealedAtUtc is required')
    } else {
        $sealedValue = $Harness.PSObject.Properties['sealedAtUtc'].Value
        $sealedAtUtc = [DateTimeOffset]::MinValue
        $parsed = if ($sealedValue -is [datetime]) {
            $sealedAtUtc = [DateTimeOffset]::new(
                ([datetime]$sealedValue).ToUniversalTime())
            $true
        } elseif ($sealedValue -is [DateTimeOffset]) {
            $sealedAtUtc = [DateTimeOffset]$sealedValue
            $true
        } else {
            [DateTimeOffset]::TryParse(
                [string]$sealedValue,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind,
                [ref]$sealedAtUtc)
        }
        if (-not $parsed) {
            $errors.Add('sealedAtUtc must be a valid ISO-8601 timestamp')
        } elseif ($sealedAtUtc.UtcDateTime -lt $SessionStartedAtUtc) {
            $errors.Add('sealedAtUtc predates the runtime session')
        } elseif ($sealedAtUtc.UtcDateTime -gt [DateTime]::UtcNow.AddMinutes(5)) {
            $errors.Add('sealedAtUtc is implausibly in the future')
        }
    }
    if ($null -ne $Harness.PSObject.Properties['runtimeDirectory']) {
        $harnessRuntime = Get-ModporterCanonicalPathText (
            [string]$Harness.runtimeDirectory)
        if (-not [string]::Equals(
                $harnessRuntime,
                $CanonicalRuntimeDirectory,
                (Get-ModporterPathComparison))) {
            $errors.Add('runtimeDirectory mismatch')
        }
    }
    foreach ($artifactError in @(Get-EmbeddedArtifactErrors `
            -Value $Harness -Label 'harness')) {
        $errors.Add($artifactError)
    }
    return $errors
}

$effectiveExpectedScenarioIds = @(
    if (@($ExpectedScenarioIds).Count -gt 0) {
        $ExpectedScenarioIds
    } elseif (-not [string]::IsNullOrWhiteSpace($ExpectedScenarioIdsCsv)) {
        $ExpectedScenarioIdsCsv -split '\|' | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        }
    }
)
$harnessContractRequested = $false
foreach ($parameterName in @(
        'HarnessReceiptPath',
        'ExpectedScenarioCount',
        'ExpectedScenarioIds',
        'ExpectedScenarioIdsCsv',
        'ExpectedProcessStartCount',
        'ExpectedWorldLoadCount'
    )) {
    if ($PSBoundParameters.ContainsKey($parameterName)) {
        $harnessContractRequested = $true
        break
    }
}
$evidenceMode = if ($harnessContractRequested) {
    'harness'
} elseif ($RuntimeKind -eq 'other') {
    'command-only'
} else {
    'lease-only'
}

$canonicalRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$canonicalRuntimeDirectory = if ([IO.Path]::IsPathRooted(
        $RuntimeDirectory)) {
    Get-ModporterCanonicalPathText $RuntimeDirectory
} else {
    Get-ModporterCanonicalPathText (Join-Path $canonicalRoot $RuntimeDirectory)
}
$resolvedExecutable = if ([IO.Path]::IsPathRooted($Executable)) {
    (Resolve-Path -LiteralPath $Executable).Path
} else {
    (Get-Command -Name $Executable -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
}
$sessionId = [guid]::NewGuid().ToString()
$startedAtUtc = [DateTime]::UtcNow
$runtimeBytes = [Text.Encoding]::UTF8.GetBytes(
    (Get-ModporterRuntimeResourceText $canonicalRuntimeDirectory))
$runtimeHash = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($runtimeBytes)).
    ToLowerInvariant()
$mutexName = "Local\modporter-runtime-$($runtimeHash.Substring(0, 24))"
$mutex = [Threading.Mutex]::new($false, $mutexName)
$runtimeLockPath = Join-Path ([IO.Path]::GetTempPath()) (
    "modporter-testing\runtime-$runtimeHash.lock")
$leaseEvidencePath = Join-Path $canonicalRoot (
    "build\modporter-testing\active-$runtimeHash-$sessionId.json")
$denialEvidencePath = Join-Path $canonicalRoot (
    "build\modporter-testing\denied-$sessionId.json")
if ([string]::IsNullOrWhiteSpace($ReceiptPath)) {
    $ReceiptPath = "build\modporter-testing\session-$sessionId.json"
}
$resolvedReceipt = Resolve-SessionPath $canonicalRoot $ReceiptPath
$resolvedHarness = if ([string]::IsNullOrWhiteSpace(
        $HarnessReceiptPath)) {
    ''
} else {
    Resolve-SessionPath $canonicalRoot $HarnessReceiptPath
}
if (Test-Path -LiteralPath $resolvedReceipt) {
    throw "Immutable session receipt already exists: $resolvedReceipt"
}
if ($harnessContractRequested -and
        [string]::IsNullOrWhiteSpace($resolvedHarness)) {
    throw 'HarnessReceiptPath is required when a harness contract is explicitly requested'
}

$oldSessionId = $env:MINECRAFT_MOD_TEST_SESSION_ID
$oldActive = $env:MODPORTER_RUNTIME_SESSION_ACTIVE
$oldActiveDirectory = $env:MODPORTER_RUNTIME_SESSION_DIRECTORY
$oldOwnerProcessId = $env:MODPORTER_RUNTIME_SESSION_OWNER_PID
$oldLegacySingle = $env:MINECRAFT_MOD_TEST_SINGLE_INSTANCE
$acquiredMutex = $false
$fileLease = $null
$leaseEvidenceWritten = $false
$processInventoryChecked = $false
$preRunProcesses = @()
$postRunProcesses = @()
$reconciledProcessIds = @()
$commandStarted = $false
$commandProcessId = 0
$commandExited = $false
$commandTimedOut = $false
$killedProcessIds = @()
$childExitCode = -1
$failure = ''
$harnessSha256 = ''
$harnessValidated = $false
$runtimeExitValidated = $false

try {
    if ($resolvedHarness -and
            (Test-Path -LiteralPath $resolvedHarness)) {
        $failure = "Immutable harness receipt already exists: $resolvedHarness"
        $childExitCode = 72
    }

    if (-not $failure) {
        try {
            $acquiredMutex = $mutex.WaitOne(
                [TimeSpan]::FromSeconds($LockTimeoutSeconds))
        } catch [Threading.AbandonedMutexException] {
            $acquiredMutex = $true
        }
        if (-not $acquiredMutex) {
            $failure = 'Runtime-directory mutex is already held'
            $childExitCode = 73
        }
    }

    if (-not $failure) {
        try {
            $lockParent = Split-Path -Parent $runtimeLockPath
            New-Item -ItemType Directory -Path $lockParent -Force |
                Out-Null
            $fileLease = [IO.File]::Open(
                $runtimeLockPath,
                [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite,
                [IO.FileShare]::None)
        } catch {
            $failure = "Runtime-directory file lease is already held: $canonicalRuntimeDirectory"
            $childExitCode = 73
        }
    }

    if (-not $failure -and $RuntimeKind -ne 'other') {
        try {
            $inventory = Get-ModporterRuntimeInventory `
                -ExcludeProcessIds @($PID) `
                -IncludePathMarkers @($canonicalRuntimeDirectory)
            $processInventoryChecked = $true
            $preRunProcesses = @($inventory.activeRuntimeProcesses)
            $reconciledProcessIds = @(
                $inventory.reconciledExitedProcessIds)
            if ($preRunProcesses.Count -gt 0) {
                $failure = 'Runtime directory already has active Minecraft processes: ' +
                    ((@($preRunProcesses | ForEach-Object {
                        $_.processId
                    })) -join ',')
                $childExitCode = 77
            }
        } catch {
            $failure = "Scoped runtime inventory failed: $($_.Exception.Message)"
            $childExitCode = 78
        }
    }

    if ($failure) {
        Write-JsonImmutable $denialEvidencePath ([ordered]@{
            schemaVersion = 1
            sessionId = $sessionId
            projectRoot = $canonicalRoot
            runtimeDirectory = $canonicalRuntimeDirectory
            runtimeKind = $RuntimeKind
            evidenceMode = $evidenceMode
            harnessContractRequested = $harnessContractRequested
            mutexName = $mutexName
            runtimeLockPath = $runtimeLockPath
            processInventoryChecked = $processInventoryChecked
            activeRuntimeProcesses = @($preRunProcesses)
            deniedAtUtc = [DateTime]::UtcNow.ToString('o')
            reason = $failure
        })
    } else {
        Write-JsonImmutable $leaseEvidencePath ([ordered]@{
            schemaVersion = 1
            sessionId = $sessionId
            ownerProcessId = $PID
            projectRoot = $canonicalRoot
            runtimeDirectory = $canonicalRuntimeDirectory
            runtimeKind = $RuntimeKind
            evidenceMode = $evidenceMode
            harnessContractRequested = $harnessContractRequested
            mutexName = $mutexName
            runtimeLockPath = $runtimeLockPath
            acquiredAtUtc = [DateTime]::UtcNow.ToString('o')
        })
        $leaseEvidenceWritten = $true
        $env:MINECRAFT_MOD_TEST_SESSION_ID = $sessionId
        $env:MODPORTER_RUNTIME_SESSION_ACTIVE = '1'
        $env:MODPORTER_RUNTIME_SESSION_DIRECTORY =
            $canonicalRuntimeDirectory
        $env:MODPORTER_RUNTIME_SESSION_OWNER_PID = [string]$PID
        $env:MINECRAFT_MOD_TEST_SINGLE_INSTANCE = '1'

        $commandResult = Invoke-OwnedCommand `
            -FilePath $resolvedExecutable `
            -Arguments $ArgumentList `
            -WorkingDirectory $canonicalRoot `
            -CommandTimeoutSeconds $TimeoutSeconds
        $commandStarted = $true
        $commandProcessId = $commandResult.processId
        $commandExited = $true
        $commandTimedOut = $commandResult.timedOut
        $killedProcessIds = @($commandResult.killedProcessIds)
        $childExitCode = $commandResult.exitCode
        if (-not [string]::IsNullOrEmpty($commandResult.stdout)) {
            Write-Output $commandResult.stdout.TrimEnd("`r", "`n")
        }
        if (-not [string]::IsNullOrEmpty($commandResult.stderr)) {
            Write-Output $commandResult.stderr.TrimEnd("`r", "`n")
        }
        if ($commandTimedOut) {
            $failure = "Runtime command timed out after $TimeoutSeconds seconds; only its owned process tree was stopped"
        } elseif ($childExitCode -ne 0) {
            $failure = "Runtime command exited with code $childExitCode"
        }

        if ($RuntimeKind -ne 'other') {
            try {
                $postInventory = Get-ModporterRuntimeInventory `
                    -ExcludeProcessIds @($PID) `
                    -IncludePathMarkers @($canonicalRuntimeDirectory)
                $postRunProcesses = @(
                    $postInventory.activeRuntimeProcesses)
                $runtimeExitValidated = $commandExited -and
                    $postRunProcesses.Count -eq 0
                if (-not $runtimeExitValidated -and -not $failure) {
                    $failure = 'Runtime command returned while scoped Minecraft processes remain active'
                    $childExitCode = 79
                }
            } catch {
                $runtimeExitValidated = $false
                if (-not $failure) {
                    $failure = "Post-run runtime inventory failed: $($_.Exception.Message)"
                    $childExitCode = 78
                }
            }
        } else {
            $runtimeExitValidated = $commandExited
        }

        if ($childExitCode -eq 0 -and $resolvedHarness) {
            if (-not (Test-Path -LiteralPath $resolvedHarness -PathType Leaf)) {
                $failure = "Harness receipt was not written: $resolvedHarness"
                $childExitCode = 74
            } else {
                try {
                    $harness = Get-Content -Raw -LiteralPath $resolvedHarness |
                        ConvertFrom-Json
                    $harnessErrors = @(Get-HarnessErrors `
                        -Harness $harness `
                        -SessionId $sessionId `
                        -CanonicalRuntimeDirectory $canonicalRuntimeDirectory `
                        -SessionStartedAtUtc $startedAtUtc)
                    if ($harnessErrors.Count -gt 0) {
                        $failure = 'Invalid harness receipt: ' +
                            ($harnessErrors -join '; ')
                        $childExitCode = 74
                    } else {
                        $harnessSha256 = (Get-FileHash `
                            -Algorithm SHA256 `
                            -LiteralPath $resolvedHarness).Hash.
                            ToLowerInvariant()
                        $harnessValidated = $true
                    }
                } catch {
                    $failure = "Harness receipt validation failed: $($_.Exception.Message)"
                    $childExitCode = 74
                }
            }
        }
    }
} catch {
    if ($childExitCode -lt 0) {
        $childExitCode = 75
    }
    if (-not $failure) {
        $failure = $_.Exception.Message
    }
} finally {
    Restore-EnvironmentValue `
        -Name 'MINECRAFT_MOD_TEST_SESSION_ID' -Value $oldSessionId
    Restore-EnvironmentValue `
        -Name 'MODPORTER_RUNTIME_SESSION_ACTIVE' -Value $oldActive
    Restore-EnvironmentValue `
        -Name 'MODPORTER_RUNTIME_SESSION_DIRECTORY' `
        -Value $oldActiveDirectory
    Restore-EnvironmentValue `
        -Name 'MODPORTER_RUNTIME_SESSION_OWNER_PID' `
        -Value $oldOwnerProcessId
    Restore-EnvironmentValue `
        -Name 'MINECRAFT_MOD_TEST_SINGLE_INSTANCE' -Value $oldLegacySingle

    if ($leaseEvidenceWritten -and
            (Test-Path -LiteralPath $leaseEvidencePath -PathType Leaf)) {
        try {
            $lease = Get-Content -Raw -LiteralPath $leaseEvidencePath |
                ConvertFrom-Json
            if ([string]$lease.sessionId -ceq $sessionId) {
                Remove-Item -LiteralPath $leaseEvidencePath -Force
            }
        } catch {
            if (-not $failure) {
                $failure = "Failed to remove owned lease evidence: $($_.Exception.Message)"
            }
            if ($childExitCode -eq 0) {
                $childExitCode = 76
            }
        }
    }
    if ($null -ne $fileLease) {
        $fileLease.Dispose()
    }
    if ($acquiredMutex) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()

    Write-JsonImmutable $resolvedReceipt ([ordered]@{
        schemaVersion = 1
        sessionId = $sessionId
        ownerProcessId = $PID
        projectRoot = $canonicalRoot
        runtimeDirectory = $canonicalRuntimeDirectory
        runtimeDirectorySha256 = $runtimeHash
        runtimeKind = $RuntimeKind
        evidenceMode = $evidenceMode
        harnessContractRequested = $harnessContractRequested
        mutexName = $mutexName
        runtimeLockPath = $runtimeLockPath
        processStartCount = if ($commandStarted) { 1 } else { 0 }
        processInventoryChecked = $processInventoryChecked
        activeRuntimeProcesses = @($preRunProcesses)
        reconciledExitedProcessIds = @($reconciledProcessIds)
        preRunRuntimeProcesses = @($preRunProcesses)
        postRunRuntimeProcesses = @($postRunProcesses)
        command = [ordered]@{
            executable = $resolvedExecutable
            arguments = @($ArgumentList)
            processId = $commandProcessId
            started = $commandStarted
            exited = $commandExited
            timedOut = $commandTimedOut
            killedOwnedProcessIds = @($killedProcessIds)
        }
        expected = [ordered]@{
            scenarioCount = if ($harnessContractRequested) {
                $ExpectedScenarioCount
            } else {
                $null
            }
            scenarioIds = @(
                if ($harnessContractRequested) {
                    $effectiveExpectedScenarioIds
                }
            )
            processStartCount = if ($harnessContractRequested) {
                $ExpectedProcessStartCount
            } else {
                $null
            }
            worldLoadCount = if ($harnessContractRequested) {
                $ExpectedWorldLoadCount
            } else {
                $null
            }
        }
        startedAtUtc = $startedAtUtc.ToString('o')
        finishedAtUtc = [DateTime]::UtcNow.ToString('o')
        exitCode = $childExitCode
        runtimeExitValidated = $runtimeExitValidated
        harnessReceiptPath = $resolvedHarness
        harnessReceiptSha256 = $harnessSha256
        harnessValidated = $harnessValidated
        passed = $childExitCode -eq 0 -and
            ($RuntimeKind -eq 'other' -or
                ($runtimeExitValidated -and
                    (-not $harnessContractRequested -or $harnessValidated)))
        failure = $failure
    })
}

if ($childExitCode -ne 0) {
    throw "ModPorter runtime session failed ($childExitCode): $failure"
}









