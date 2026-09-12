# SayType tray icon + background service manager
# Started by 启动服务.bat
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName PresentationFramework

$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root
$Port = 8787
$StatusFile = Join-Path $Root 'runtime\status.json'
$LogFile = Join-Path $Root 'runtime\server.log'
$ErrFile = Join-Path $Root 'runtime\server.err.log'

function Test-PortListening {
    param([int]$PortNum)
    $lines = netstat -ano | Select-String ":$PortNum\s.*LISTENING"
    if (-not $lines) { return $null }
    $line = $lines | Select-Object -First 1
    if ($line -match 'LISTENING\s+(\d+)') { return [int]$Matches[1] }
    return $null
}

function New-TrayIcon {
    $bmp = New-Object System.Drawing.Bitmap 32, 32
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 26, 115, 232))
    $g.FillEllipse($brush, 1, 1, 30, 30)
    $font = New-Object System.Drawing.Font 'Segoe UI', 14, ([System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = 'Center'
    $sf.LineAlignment = 'Center'
    $white = [System.Drawing.Brushes]::White
    $g.DrawString('S', $font, $white, [System.Drawing.RectangleF]::new(0, 0, 32, 32), $sf)
    $g.Dispose()
    $icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
    return $icon
}

function Show-Pin {
    $j = $null
    if (Test-Path $StatusFile) {
        try { $j = Get-Content -Raw $StatusFile | ConvertFrom-Json } catch { }
    }
    if (-not $j) {
        [void][System.Windows.MessageBox]::Show('服务未运行或无法读取 status.json', 'SayType', 'OK', 'Warning')
        return
    }
    $addrs = @()
    if ($j.addrs) { foreach ($a in $j.addrs) { $addrs += ("ws://{0}:{1}" -f $a, $j.port) } }
    $addrText = if ($addrs.Count) { $addrs -join [Environment]::NewLine } else { '(no LAN IP)' }
    $msg = "PIN: " + $j.pin + [Environment]::NewLine + [Environment]::NewLine + "地址:" + [Environment]::NewLine + $addrText + [Environment]::NewLine + [Environment]::NewLine + "PID: " + $j.pid
    [void][System.Windows.MessageBox]::Show($msg, 'SayType PIN', 'OK', 'Information')
}

function Show-Qr {
    $qrPath = Join-Path $Root 'runtime\config-qr.png'
    $j = $null
    if (Test-Path $StatusFile) {
        try { $j = Get-Content -Raw $StatusFile | ConvertFrom-Json } catch { }
    }
    if (-not $j) {
        [void][System.Windows.MessageBox]::Show('服务未运行', 'SayType', 'OK', 'Warning')
        return
    }
    if (-not (Test-Path $qrPath) -and $j.configUri) {
        $env:PHONE_TYPE_QR_URI = $j.configUri
        $env:PHONE_TYPE_QR_PATH = $qrPath
        & node -e "import('qrcode').then(async m=>{await m.default.toFile(process.env.PHONE_TYPE_QR_PATH,process.env.PHONE_TYPE_QR_URI,{width:320,margin:2});})"
    }
    if (Test-Path $qrPath) {
        Start-Process $qrPath
    } else {
        [void][System.Windows.MessageBox]::Show('二维码生成失败，可手动填写 IP/PIN', 'SayType', 'OK', 'Error')
    }
}

function Start-NodeService {
    New-Item -ItemType Directory -Force -Path (Join-Path $Root 'runtime') | Out-Null
    Remove-Item $StatusFile -ErrorAction SilentlyContinue
    $env:PHONE_TYPE_STATUS_FILE = $StatusFile
    $script:nodeProc = Start-Process -FilePath 'node' `
        -ArgumentList 'server/index.mjs' `
        -WorkingDirectory $Root `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $LogFile `
        -RedirectStandardError $ErrFile
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $StatusFile) {
            try {
                $st = Get-Content -Raw $StatusFile | ConvertFrom-Json
                if ($st.pin) { return $st }
            } catch { }
        }
        Start-Sleep -Milliseconds 150
    }
    return $null
}

function Stop-NodeService {
    $pids = @()
    if ($script:nodeProc -and -not $script:nodeProc.HasExited) {
        $pids += $script:nodeProc.Id
    }
    $listen = Test-PortListening -PortNum $Port
    if ($listen) { $pids += $listen }
    if (Test-Path $StatusFile) {
        try {
            $j = Get-Content -Raw $StatusFile | ConvertFrom-Json
            if ($j.pid) { $pids += $j.pid }
        } catch { }
    }
    $pids | Select-Object -Unique | ForEach-Object {
        try { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } catch { }
    }
    Remove-Item $StatusFile -ErrorAction SilentlyContinue
}

# --- port occupied? ---
$oldPid = Test-PortListening -PortNum $Port
if ($oldPid) {
    try { $name = (Get-Process -Id $oldPid -ErrorAction Stop).ProcessName } catch { $name = 'unknown' }
    $ans = [System.Windows.MessageBox]::Show(
        "端口 $Port 已被 $name (PID $oldPid) 占用。`n结束它并启动新服务？",
        'SayType',
        'YesNo',
        'Question'
    )
    if ($ans -eq 'Yes') {
        try { Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue } catch { }
        Start-Sleep -Milliseconds 400
    } else {
        [void][System.Windows.MessageBox]::Show('未启动新服务。', 'SayType', 'OK', 'Information')
        exit 0
    }
}

# --- tray ---
$script:tray = New-Object System.Windows.Forms.NotifyIcon
$script:tray.Icon = New-TrayIcon
$script:tray.Text = 'SayType 启动中…'
$script:tray.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$miQr = New-Object System.Windows.Forms.ToolStripMenuItem '显示二维码（扫码配置）'
$miPin = New-Object System.Windows.Forms.ToolStripMenuItem '显示 PIN / 地址'
$miRestart = New-Object System.Windows.Forms.ToolStripMenuItem '重启服务'
$miExit = New-Object System.Windows.Forms.ToolStripMenuItem '停止服务并退出'
$menu.Items.Add($miQr) | Out-Null
$menu.Items.Add($miPin) | Out-Null
$menu.Items.Add($miRestart) | Out-Null
$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null
$menu.Items.Add($miExit) | Out-Null
$script:tray.ContextMenuStrip = $menu

$miQr.Add_Click({ Show-Qr })
$miPin.Add_Click({ Show-Pin })
$miRestart.Add_Click({
    Stop-NodeService
    Start-Sleep -Milliseconds 300
    $st = Start-NodeService
    if ($st) {
        $script:tray.Text = "SayType 运行中 PIN $($st.pin)"
        $script:tray.ShowBalloonTip(3000, 'SayType', "已重启，PIN: $($st.pin)", 'Info')
    } else {
        $script:tray.Text = 'SayType 启动失败'
        [void][System.Windows.MessageBox]::Show('重启失败，请查看 runtime\server.err.log', 'SayType', 'OK', 'Error')
    }
})
$miExit.Add_Click({
    Stop-NodeService
    $script:tray.Visible = $false
    $script:tray.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
$script:tray.Add_DoubleClick({ Show-Qr })

# start service
$st = Start-NodeService
if ($st) {
    $script:tray.Text = "SayType 运行中 PIN $($st.pin)"
    $script:tray.ShowBalloonTip(4000, 'SayType', "已在后台运行`nPIN: $($st.pin)`n双击图标可再次查看", 'Info')
} else {
    $script:tray.Text = 'SayType 启动失败'
    [void][System.Windows.MessageBox]::Show('服务启动失败，请查看 runtime\server.err.log', 'SayType', 'OK', 'Error')
}

# keep-alive loop: update tooltip if process dies
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 5000
$timer.Add_Tick({
    $ok = $false
    if ($script:nodeProc -and -not $script:nodeProc.HasExited) { $ok = $true }
    elseif (Test-PortListening -PortNum $Port) { $ok = $true }
    if (-not $ok -and $script:tray) {
        $script:tray.Text = 'SayType 已停止（点重启）'
    }
})
$timer.Start()

[System.Windows.Forms.Application]::Run()
