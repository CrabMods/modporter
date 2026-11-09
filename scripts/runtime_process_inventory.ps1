[CmdletBinding()]
param(
    [switch]$FailIfDetected,
    [string]$ExcludeProcessIdsCsv = '',
    [string]$IncludePathMarkersCsv = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ModporterPathComparison {
    if ($IsWindows) {
        return [StringComparison]::OrdinalIgnoreCase
    }
    return [StringComparison]::Ordinal
}

function Get-ModporterMinecraftMarker {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$CommandLine)

    $patterns = [ordered]@{
        'gradle-minecraft-runtime-task' =
            '(?is)gradle-wrapper\.jar.*\b(runClient|runServer|runGameTestServer|clientGameTest|serverGameTest)\b'
        'forge-bootstrap-runtime' =
            '(?is)cpw\.mods\.bootstraplauncher\.BootstrapLauncher.*--launchTarget\s+\S*(client|server|game[test]*)\S*'
        'mojang-runtime-main' =
            '(?is)net\.minecraft\.(client\.main|server)\.Main'
        'knot-runtime-main' =
            '(?is)(fabric|quilt).*loader.*launch.*knot\.Knot(Client|Server)'
        'fabric-server-runtime' = '(?is)fabric-server-launch\.jar'
    }
    foreach ($entry in $patterns.GetEnumerator()) {
        if ($CommandLine -match $entry.Value) {
            return $entry.Key
        }
    }
    return ''
}

function Test-ModporterPathMarker {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$CommandLine,
        [Parameter(Mandatory)][string[]]$PathMarkers
    )

    $markers = @($PathMarkers | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    })
    if ($markers.Count -eq 0) {
        return $true
    }
    $normalizedCommand = $CommandLine.Replace('\', '/')
    $pathComparison = Get-ModporterPathComparison
    foreach ($marker in $markers) {
        $normalizedMarker = ([IO.Path]::GetFullPath($marker)).
            Replace('\', '/').TrimEnd('/')
        if ($normalizedCommand.IndexOf(
                $normalizedMarker,
                $pathComparison) -ge 0) {
            return $true
        }
    }
    return $false
}

function Get-ModporterJavaInspectionText {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$CommandLine)

    $parts = [Collections.Generic.List[string]]::new()
    $parts.Add($CommandLine)
    $pattern = '(?:^|\s)@(?:"(?<quoted>[^"]+)"|(?<plain>[^\s]+))'
    foreach ($match in [regex]::Matches($CommandLine, $pattern)) {
        $reportedPath = if ($match.Groups['quoted'].Success) {
            $match.Groups['quoted'].Value
        } else {
            $match.Groups['plain'].Value
        }
        if (-not [IO.Path]::IsPathRooted($reportedPath) -or
                $reportedPath.StartsWith('\\')) {
            throw 'A Java argument file is not an absolute local path'
        }
        $argumentFile = [IO.Path]::GetFullPath($reportedPath)
        $item = Get-Item -LiteralPath $argumentFile -Force -ErrorAction Stop
        if ($item.PSIsContainer -or
                ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Java argument file is not a direct regular file: $argumentFile"
        }
        if ($item.Length -gt 2MB) {
            throw "Java argument file exceeds the 2 MiB inspection limit: $argumentFile"
        }
        $parts.Add((Get-Content -Raw -LiteralPath $argumentFile -ErrorAction Stop))
    }
    return $parts -join "`n"
}
function Get-ModporterRuntimeProcesses {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Processes,
        [int[]]$ExcludeProcessIds = @(),
        [string[]]$IncludePathMarkers = @()
    )

    $runtimeRows = [Collections.Generic.List[object]]::new()
    foreach ($process in $Processes) {
        $processId = [int]$process.ProcessId
        if ($ExcludeProcessIds -contains $processId) {
            continue
        }
        $name = [string]$process.Name
        $commandLine = [string]$process.CommandLine
        if ($name -notmatch '^(java|javaw)(?:\.exe)?$' -or
                [string]::IsNullOrWhiteSpace($commandLine)) {
            continue
        }
        $inspectionText = Get-ModporterJavaInspectionText `
            -CommandLine $commandLine
        $marker = Get-ModporterMinecraftMarker `
            -CommandLine $inspectionText
        if ([string]::IsNullOrWhiteSpace($marker) -or
                -not (Test-ModporterPathMarker `
                    -CommandLine $inspectionText `
                    -PathMarkers $IncludePathMarkers)) {
            continue
        }
        $pathComparison = Get-ModporterPathComparison
        $matchedPath = @($IncludePathMarkers | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_) -and
            $inspectionText.Replace('\', '/').IndexOf(
                ([IO.Path]::GetFullPath($_)).Replace('\', '/').TrimEnd('/'),
                $pathComparison) -ge 0
        } | Select-Object -First 1)
        $runtimeRows.Add([pscustomobject][ordered]@{
            processId = $processId
            name = $name
            marker = $marker
            matchedPath = if ($matchedPath.Count -eq 0) {
                ''
            } else {
                [IO.Path]::GetFullPath([string]$matchedPath[0])
            }
        })
    }
    return @($runtimeRows | Sort-Object processId -Unique)
}

function Get-ModporterRuntimeInventory {
    [CmdletBinding()]
    param(
        [int[]]$ExcludeProcessIds = @(),
        [string[]]$IncludePathMarkers = @()
    )

    $reconciled = [Collections.Generic.List[int]]::new()
    for ($observation = 0; $observation -lt 3; $observation++) {
        $inventory = @(Get-CimInstance Win32_Process -ErrorAction Stop)
        $java = @($inventory | Where-Object {
            $_.Name -match '^(java|javaw)(?:\.exe)?$' -and
            $ExcludeProcessIds -notcontains [int]$_.ProcessId
        })
        $unknown = @($java | Where-Object {
            [string]::IsNullOrWhiteSpace([string]$_.CommandLine)
        })
        if ($unknown.Count -eq 0) {
            return [pscustomobject][ordered]@{
                processInventoryChecked = $true
                activeRuntimeProcesses = @(
                    Get-ModporterRuntimeProcesses `
                        -Processes $java `
                        -ExcludeProcessIds $ExcludeProcessIds `
                        -IncludePathMarkers $IncludePathMarkers)
                reconciledExitedProcessIds = $reconciled.ToArray()
            }
        }

        foreach ($entry in $unknown) {
            $process = $null
            try {
                try {
                    $process = [Diagnostics.Process]::GetProcessById(
                        [int]$entry.ProcessId)
                    $null = $process.Handle
                } catch [ArgumentException] {
                    $process = $null
                }
                if ($null -ne $process -and -not $process.WaitForExit(250)) {
                    throw "Java process $($entry.ProcessId) is alive but its command line cannot be inspected"
                }
                $reconciled.Add([int]$entry.ProcessId)
            } finally {
                if ($null -ne $process) {
                    $process.Dispose()
                }
            }
        }
    }
    throw 'Java runtime inventory could not reconcile uninspectable processes'
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        $excluded = if ([string]::IsNullOrWhiteSpace($ExcludeProcessIdsCsv)) {
            @()
        } else {
            @($ExcludeProcessIdsCsv -split ',' | ForEach-Object {
                [int]$_.Trim()
            })
        }
        $markers = if ([string]::IsNullOrWhiteSpace(
                $IncludePathMarkersCsv)) {
            @()
        } else {
            @($IncludePathMarkersCsv -split '\|' | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_)
            })
        }
        $result = Get-ModporterRuntimeInventory `
            -ExcludeProcessIds $excluded `
            -IncludePathMarkers $markers
        $result | ConvertTo-Json -Depth 8 -Compress | Write-Output
        if ($FailIfDetected -and
                @($result.activeRuntimeProcesses).Count -gt 0) {
            exit 77
        }
        exit 0
    } catch {
        Write-Error "runtime process inventory failed: $($_.Exception.Message)"
        exit 78
    }
}


