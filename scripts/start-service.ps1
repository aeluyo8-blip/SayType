# SayType service launcher
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root
$Port = 8787
$StatusFile = Join-Path $Root 'runtime\status.json'
$LogFile = Join-Path $Root 'runtime\server.log'

function Write-Banner {
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  SayType service" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""
}

function Test-PortListening {
    param([int]$PortNum)
    $lines = netstat -ano | Select-String ":$PortNum\s.*LISTENING"
    if (-not $lines) { return $null }
    $line = $lines | Select-Object -First 1
    if ($line -match 'LISTENING\s+(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Show-PinPopup {
    param([string]$Pin, [object]$Addrs)
    $lines = @()
    if ($Addrs) {
        foreach ($a in $Addrs) { $lines += ("ws://{0}:{1}" -f $a, $Port) }
    } else {
        $lines += "(no LAN IP)"
    }
    $addrText = $lines -join [Environment]::NewLine
    $msg = "SayType is running in background." + [Environment]::NewLine + [Environment]::NewLine + "PIN:" + [Environment]::NewLine + $Pin + [Environment]::NewLine + [Environment]::NewLine + "Address:" + [Environment]::NewLine + $addrText + [Environment]::NewLine + [Environment]::NewLine + "Stop: double-click stop-service.bat" + [Environment]::NewLine + "Show PIN again: double-click show-pin.bat"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($msg))
    $script = "Add-Type -AssemblyName PresentationFramework; `$m=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$b64')); [void][System.Windows.MessageBox]::Show(`$m,'SayType','OK','Information')"
    Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-STA','-Command',$script | Out-Null
}

Write-Banner

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "[!] Node.js not found (need Node 20+)" -ForegroundColor Yellow
    Write-Host "[i] Opening download page..."
    Start-Process "https://nodejs.org/zh-cn/download"
    Read-Host "Press Enter to close"
    exit 1
}

if (-not (Test-Path "node_modules\ws")) {
    Write-Host "[..] First run, installing deps..."
    npm install --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[!] npm install failed" -ForegroundColor Red
        Read-Host "Press Enter to close"
        exit 1
    }
    Write-Host "[+] deps ready"
    Write-Host ""
}

$ruleOutput = netsh advfirewall firewall show rule name="phone-type-8787" 2>$null | Out-String
if ($ruleOutput -notmatch 'phone-type-8787') {
    Write-Host "[i] Need firewall allow for 8787, click Yes in UAC dialog..."
    $proc = Start-Process powershell -Verb RunAs -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile','-Command',"netsh advfirewall firewall add rule name='phone-type-8787' dir=in action=allow protocol=TCP localport=8787"
    $proc.WaitForExit()
    if ($proc.ExitCode -eq 0) {
        Write-Host "[+] firewall 8787 allowed" -ForegroundColor Green
    } else {
        Write-Host "[i] firewall not allowed; try run bat as admin if phone cannot connect" -ForegroundColor Yellow
    }
    Write-Host ""
}

$oldPid = Test-PortListening -PortNum $Port
if ($oldPid) {
    try {
        $oldProc = Get-Process -Id $oldPid -ErrorAction Stop
        $name = $oldProc.ProcessName
    } catch {
        $name = "unknown"
    }
    Write-Host "Port $Port is used by $name (PID $oldPid)" -ForegroundColor Yellow
    $ans = Read-Host "Kill it and start new service? [Y/N]"
    if ($ans -match '^[Yy]') {
        taskkill /PID $oldPid /F 2>$null | Out-Null
        Start-Sleep -Milliseconds 400
        $still = Test-PortListening -PortNum $Port
        if ($still) {
            Write-Host "[!] Failed to kill PID $still" -ForegroundColor Red
            Read-Host "Press Enter to close"
            exit 1
        }
        Write-Host "[+] old process killed" -ForegroundColor Green
    } else {
        Write-Host "[i] new service not started (old one still running)"
        Read-Host "Press Enter to close"
        exit 0
    }
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'runtime') | Out-Null
Remove-Item $StatusFile -ErrorAction SilentlyContinue

Write-Host "[..] starting service in background (no black window)..."
$env:PHONE_TYPE_STATUS_FILE = $StatusFile
$null = Start-Process -FilePath "node" -ArgumentList "server/index.mjs" -WorkingDirectory $Root -WindowStyle Hidden -PassThru -RedirectStandardOutput $LogFile -RedirectStandardError (Join-Path $Root 'runtime\server.err.log')

$deadline = (Get-Date).AddSeconds(5)
$status = $null
while ((Get-Date) -lt $deadline) {
    if (Test-Path $StatusFile) {
        try {
            $status = Get-Content $StatusFile -Raw | ConvertFrom-Json
            if ($status.pin) { break }
        } catch { }
    }
    Start-Sleep -Milliseconds 200
}

if (-not $status -or -not $status.pin) {
    Write-Host "[!] start failed, see runtime\server.log and server.err.log" -ForegroundColor Red
    Read-Host "Press Enter to close"
    exit 1
}

Write-Host "[+] running in background (PID $($status.pid))" -ForegroundColor Green
Write-Host "    PIN: $($status.pin)"
Write-Host "    stop: stop-service.bat | show PIN: show-pin.bat"
Write-Host ""

if ($env:PHONE_TYPE_NO_POPUP -ne '1') {
    Show-PinPopup -Pin $status.pin -Addrs $status.addrs
}

Write-Host "closing in 3s..."
Start-Sleep -Seconds 3
exit 0