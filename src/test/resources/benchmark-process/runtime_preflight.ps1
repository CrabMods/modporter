param(
    [Parameter(Mandatory)][string]$Guard,
    [Parameter(Mandatory)][string]$ExpectedGuardSha256,
    [Parameter(Mandatory)][string]$Scope
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if ((Get-FileHash -LiteralPath $Guard -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedGuardSha256) {
    throw 'Runtime inventory library differs from the declared project source bytes'
}
. $Guard
function Read-PreflightState($Inventory){
    $byId=@{}
    foreach($entry in $Inventory){$byId[[int]$entry.ProcessId]=$entry}
    $cursor=$byId[[int]$PID]
    if($null -eq $cursor){throw 'Current launcher is absent from the process inventory'}
    $excluded=[Collections.Generic.HashSet[int]]::new()
    while($null -ne $cursor -and $excluded.Add([int]$cursor.ProcessId)){
        $parent=$byId[[int]$cursor.ParentProcessId]
        if($null -eq $parent){break}
        if($null -eq $parent.CreationDate -or $null -eq $cursor.CreationDate -or
            [datetime]$parent.CreationDate -gt [datetime]$cursor.CreationDate){break}
        $cursor=$parent
    }
    return [pscustomobject]@{byId=$byId;excluded=$excluded;java=@($Inventory|Where-Object {$_.Name -match '^(java|javaw)\.exe$'})}
}
$state=Read-PreflightState @(Get-CimInstance Win32_Process -ErrorAction Stop)
$reconciled=[Collections.Generic.List[int]]::new()
for($observation=0;$observation -lt 3;$observation++){
    $unknown=@($state.java|Where-Object {!$state.excluded.Contains([int]$_.ProcessId) -and [string]::IsNullOrWhiteSpace([string]$_.CommandLine)})
    if(!$unknown.Count){break}
    foreach($entry in $unknown){
        $process=$null
        try{
            try{$process=[Diagnostics.Process]::GetProcessById([int]$entry.ProcessId);$null=$process.Handle}catch [ArgumentException]{}
            if($null -ne $process -and !$process.WaitForExit(250)){throw "A Java process cannot be inspected and remains alive: $($entry.ProcessId)"}
        }finally{if($null -ne $process){$process.Dispose()}}
    }
    $next=Read-PreflightState @(Get-CimInstance Win32_Process -ErrorAction Stop)
    foreach($entry in $unknown){
        if($next.byId.ContainsKey([int]$entry.ProcessId)){throw "A Java process cannot be inspected or reconciled to an absent PID: $($entry.ProcessId)"}
        $reconciled.Add([int]$entry.ProcessId)
    }
    $state=$next
}
$byId=$state.byId;$excluded=$state.excluded;$java=$state.java
if(@($java|Where-Object {!$excluded.Contains([int]$_.ProcessId) -and [string]::IsNullOrWhiteSpace([string]$_.CommandLine)}).Count){throw 'A Java process cannot be inspected; runtime absence is unproven'}
$normalized=$Scope.Replace('\','/').TrimEnd('/')
$pattern='(?i)(^|[\s"''=;:%@])'+[regex]::Escape($normalized)+'($|[/\s"'';:%])'
$candidates=@(Get-ModporterRuntimeProcesses -Processes $java -ExcludeProcessIds @($excluded) `
    -IncludePathMarkers @($Scope,$normalized))
$active=@($candidates|Where-Object {
    ([string]$byId[[int]$_.processId].CommandLine).Replace('\','/') -match $pattern
})
[ordered]@{schema='modporter.runtime-preflight.v1';processInventoryChecked=$true;scope=$Scope;
    activeRuntimeProcesses=@($active);reconciledExitedProcessIds=$reconciled.ToArray();exclusionScope='CURRENT_LAUNCHER_AND_PROVED_ANCESTORS_NOT_THEIR_OTHER_DESCENDANTS'}|
    ConvertTo-Json -Depth 5 -Compress|Write-Output
if($active.Count){exit 77}
exit 0
