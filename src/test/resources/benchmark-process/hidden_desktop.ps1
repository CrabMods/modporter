# Runs a process on a private, never-switched-to Windows desktop so its windows are invisible
# and can neither take focus nor receive hardware input, while stdout/stderr stay inherited
# (the benchmark redirects them to a log). `-Probe` prints the calling thread's desktop name,
# which is how the harness verifies that children really land on the hidden desktop.
param(
    [string]$Executable,
    [string[]]$ArgumentList = @(),
    [string]$DesktopName = 'modporter-hidden',
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -Namespace ModPorter -Name HiddenDesktop -MemberDefinition @'
[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
public struct STARTUPINFO {
    public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
    public int dwX; public int dwY; public int dwXSize; public int dwYSize; public int dwXCountChars; public int dwYCountChars;
    public int dwFillAttribute; public int dwFlags; public short wShowWindow; public short cbReserved2;
    public IntPtr lpReserved2; public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
}
[StructLayout(LayoutKind.Sequential)]
public struct PROCESS_INFORMATION { public IntPtr hProcess; public IntPtr hThread; public int dwProcessId; public int dwThreadId; }
[DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern IntPtr CreateDesktop(string name, IntPtr device, IntPtr devmode, int flags, uint desiredAccess, IntPtr attributes);
[DllImport("user32.dll", SetLastError = true)]
public static extern IntPtr GetThreadDesktop(int threadId);
[DllImport("kernel32.dll")]
public static extern int GetCurrentThreadId();
[DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool GetUserObjectInformation(IntPtr obj, int index, System.Text.StringBuilder info, int length, out int needed);
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern bool CreateProcess(string application, string commandLine, IntPtr processAttributes, IntPtr threadAttributes,
    bool inheritHandles, uint creationFlags, IntPtr environment, string currentDirectory, ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInformation);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetExitCodeProcess(IntPtr process, out int exitCode);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool CloseHandle(IntPtr handle);

public static string CurrentDesktopName() {
    IntPtr desktop = GetThreadDesktop(GetCurrentThreadId());
    var buffer = new System.Text.StringBuilder(256);
    int needed;
    if (!GetUserObjectInformation(desktop, 2, buffer, 256, out needed)) {
        throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
    }
    return buffer.ToString();
}

/// Starts commandLine on the named desktop with inherited standard handles and waits for it.
public static int Launch(string desktop, string commandLine, string currentDirectory) {
    // GENERIC_ALL; the desktop is never activated with SwitchDesktop, so nothing on it is shown
    if (CreateDesktop(desktop, IntPtr.Zero, IntPtr.Zero, 0, 0x10000000, IntPtr.Zero) == IntPtr.Zero) {
        throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "CreateDesktop");
    }
    STARTUPINFO startup = new STARTUPINFO();
    startup.cb = Marshal.SizeOf(typeof(STARTUPINFO));
    startup.lpDesktop = desktop;
    PROCESS_INFORMATION info;
    if (!CreateProcess(null, commandLine, IntPtr.Zero, IntPtr.Zero, true, 0, IntPtr.Zero, currentDirectory, ref startup, out info)) {
        throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "CreateProcess");
    }
    try {
        WaitForSingleObject(info.hProcess, 0xFFFFFFFF);
        int exitCode;
        GetExitCodeProcess(info.hProcess, out exitCode);
        return exitCode;
    } finally {
        CloseHandle(info.hThread);
        CloseHandle(info.hProcess);
    }
}
'@

if ($Probe) {
    Write-Output ('desktop=' + [ModPorter.HiddenDesktop]::CurrentDesktopName())
    exit 0
}
if ([string]::IsNullOrWhiteSpace($Executable)) { throw 'Executable is required' }

function ConvertTo-CommandLineArgument([string]$value) {
    if ($value -notmatch '[\s"]') { return $value }
    return '"' + ($value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

$commandLine = (@(ConvertTo-CommandLineArgument $Executable) + @($ArgumentList | ForEach-Object { ConvertTo-CommandLineArgument $_ })) -join ' '
$exitCode = [ModPorter.HiddenDesktop]::Launch($DesktopName, $commandLine, (Get-Location).ProviderPath)
exit $exitCode
