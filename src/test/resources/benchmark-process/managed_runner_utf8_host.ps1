[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ManagedRunner,

    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [ValidateSet('client', 'gametest-server', 'server', 'other')]
    [string]$RuntimeKind = 'client',

    [string]$RuntimeDirectory,

    [string]$ReceiptPath,

    [string]$HarnessReceiptPath,

    [int]$ExpectedScenarioCount,

    [int]$ExpectedProcessStartCount,

    [int]$ExpectedWorldLoadCount
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$global:OutputEncoding = $utf8

$managedParameters = @{
    ProjectRoot = $ProjectRoot
    Executable = $Executable
    RuntimeKind = $RuntimeKind
}
foreach ($name in @(
        'RuntimeDirectory',
        'ReceiptPath',
        'HarnessReceiptPath',
        'ExpectedScenarioCount',
        'ExpectedProcessStartCount',
        'ExpectedWorldLoadCount'
    )) {
    if ($PSBoundParameters.ContainsKey($name)) {
        $managedParameters[$name] = $PSBoundParameters[$name]
    }
}

& $ManagedRunner @managedParameters
if ($null -ne $LASTEXITCODE) {
    exit $LASTEXITCODE
}
