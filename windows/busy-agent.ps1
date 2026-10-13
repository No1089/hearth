<#
busy-agent for Windows: tells Hearth when this PC is on a call.

The same contract as mac/busy-agent: POST {source, busy, devices} to
<hearth>/api/busy on every change and every 30 s as a heartbeat. A camera in use
counts at once; a microphone after 8 s of continuous use.

How it knows: Windows records, for every app that has used the camera or the
microphone, a LastUsedTimeStart and a LastUsedTimeStop under
HK(CU|LM)\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\webcam
and \microphone. Stop is 0 while the app has the device open. Packaged apps are
subkeys; desktop apps are under NonPackaged, named by their path with # for \.
Device names aren't recorded there, so this reports "camera" and "microphone",
and -Ignore takes those app keys, e.g. a virtual camera that is always on.

  powershell -ExecutionPolicy Bypass -File busy-agent.ps1 https://hearth.example.com [-Ignore app,app] [-Once]
  powershell -ExecutionPolicy Bypass -File busy-agent.ps1 -Install https://hearth.example.com   # run at logon
  powershell -ExecutionPolicy Bypass -File busy-agent.ps1 -Uninstall
#>
param(
    [Parameter(Position = 0)] [string]$Hearth,
    [string[]]$Ignore = @(),
    [switch]$Once,
    [string]$Install,
    [switch]$Uninstall
)
$MicGrace = 8; $Poll = 2; $Heartbeat = 30
$TaskName = 'Hearth busy agent'
$Dir = Join-Path $env:LOCALAPPDATA 'hearth'
$LogFile = Join-Path $Dir 'busy-agent.log'

function Log([string]$msg) {
    $line = "$((Get-Date).ToString('s')) $msg"
    Write-Host $line
    try { New-Item -ItemType Directory -Force $Dir | Out-Null; Add-Content $LogFile $line } catch {}
}

# -- install / uninstall ------------------------------------------------------

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $Dir 'busy-agent.ps1') -ErrorAction SilentlyContinue
    'removed'; exit 0
}
if ($Install) {
    New-Item -ItemType Directory -Force $Dir | Out-Null
    $script = Join-Path $Dir 'busy-agent.ps1'
    Copy-Item $PSCommandPath $script -Force
    $taskArgs = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`" $Install"
    if ($Ignore.Count) { $taskArgs += ' -Ignore ' + (($Ignore | ForEach-Object { "`"$_`"" }) -join ',') }
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $taskArgs
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    "installed: $TaskName -> $Install (log: $LogFile)"; exit 0
}
if (-not $Hearth) {
    Write-Error 'usage: busy-agent.ps1 <hearth url> [-Ignore app,app] [-Once]'; exit 2
}
$Url = $Hearth.TrimEnd('/') + '/api/busy'

# -- devices ------------------------------------------------------------------

function AppsUsing([string]$device) {
    $using = @()
    foreach ($hive in 'HKCU:', 'HKLM:') {
        $root = "$hive\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\$device"
        if (-not (Test-Path $root)) { continue }
        $keys = @(Get-ChildItem $root -ErrorAction SilentlyContinue | Where-Object PSChildName -ne 'NonPackaged')
        $keys += @(Get-ChildItem "$root\NonPackaged" -ErrorAction SilentlyContinue)
        foreach ($k in $keys) {
            $p = Get-ItemProperty $k.PSPath -ErrorAction SilentlyContinue
            if ($p.LastUsedTimeStart -gt 0 -and $p.LastUsedTimeStop -eq 0 -and $Ignore -notcontains $k.PSChildName) {
                $using += $k.PSChildName
            }
        }
    }
    @($using | Sort-Object -Unique)
}

# -- reporting ----------------------------------------------------------------

$script:Failing = $false   # log failures once, not every heartbeat
function Report([bool]$busy, [string[]]$devices) {
    $body = @{ source = $env:COMPUTERNAME; busy = $busy; devices = @($devices) } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Method Post -Uri $Url -ContentType 'application/json' -Body $body -TimeoutSec 5 | Out-Null
        if ($script:Failing) { Log 'reports reaching Hearth again'; $script:Failing = $false }
    } catch {
        if (-not $script:Failing) { Log "report failed: $($_.Exception.Message) (will keep trying quietly)" }
        $script:Failing = $true
    }
}

# -- main ---------------------------------------------------------------------

$micSince = $null; $lastBusy = $null; $lastSent = [DateTime]::MinValue
while ($true) {
    $cams = AppsUsing 'webcam'
    $mics = AppsUsing 'microphone'
    if ($mics.Count -eq 0) { $micSince = $null } elseif ($null -eq $micSince) { $micSince = Get-Date }
    $micBusy = ($null -ne $micSince) -and (((Get-Date) - $micSince).TotalSeconds -ge $MicGrace)
    $busy = ($cams.Count -gt 0) -or $micBusy
    $devices = @()
    if ($cams.Count -gt 0) { $devices += 'camera' }
    if ($micBusy) { $devices += 'microphone' }

    if ($Once) {
        "camera apps: [$($cams -join ', ')], microphone apps: [$($mics -join ', ')] -> busy=$busy"
        Report $busy $devices
        exit 0
    }
    if ($busy -ne $lastBusy -or ((Get-Date) - $lastSent).TotalSeconds -ge $Heartbeat) {
        if ($busy -ne $lastBusy) { Log $(if ($busy) { "BUSY ($($devices -join ', '))" } else { 'free' }) }
        Report $busy $devices
        $lastBusy = $busy; $lastSent = Get-Date
    }
    Start-Sleep -Seconds $Poll
}
