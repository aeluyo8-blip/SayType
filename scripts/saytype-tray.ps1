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

function Get-Status {
    if (-not (Test-Path $StatusFile)) { return $null }
    try { return (Get-Content -Raw $StatusFile | ConvertFrom-Json) } catch { return $null }
}

function Ensure-QrFile {
    param($j)
    $qrPath = Join-Path $Root 'runtime\config-qr.png'
    if (-not $j -or -not $j.configUri) { return $null }
    # always regenerate so PIN/IP is current
    if (Test-Path $qrPath) {
        try { Remove-Item $qrPath -Force } catch { }
    }
    $env:PHONE_TYPE_QR_URI = $j.configUri
    $env:PHONE_TYPE_QR_PATH = $qrPath
    & node -e "import('qrcode').then(async m=>{await m.default.toFile(process.env.PHONE_TYPE_QR_PATH,process.env.PHONE_TYPE_QR_URI,{width:360,margin:2,color:{dark:'#101828',light:'#FFFFFF'}});})"
    if (Test-Path $qrPath) { return $qrPath }
    return $null
}

function Show-Qr {
    $j = Get-Status
    if (-not $j) {
        [void][System.Windows.MessageBox]::Show('服务未运行或无法读取 status.json', 'SayType', 'OK', 'Warning')
        return
    }
    $qrPath = Ensure-QrFile -j $j
    if (-not $qrPath) {
        [void][System.Windows.MessageBox]::Show('二维码生成失败，可手动填写 IP/PIN', 'SayType', 'OK', 'Error')
        return
    }

    $addrs = @()
    if ($j.addrs) { foreach ($a in $j.addrs) { $addrs += ("ws://{0}:{1}" -f $a, $j.port) } }
    $addrText = if ($addrs.Count) { $addrs -join '   ' } else { '(no LAN IP)' }

    $form = New-Object System.Windows.Forms.Form
    $form.Text = 'SayType 扫码配置'
    $form.FormBorderStyle = 'FixedDialog'
    $form.StartPosition = 'CenterScreen'
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true
    $form.BackColor = [System.Drawing.Color]::FromArgb(248, 250, 252)
    $form.ClientSize = New-Object System.Drawing.Size(360, 470)
    $form.FormBorderStyle = 'SizableToolWindow'

    $title = New-Object System.Windows.Forms.Label
    $title.Text = '用手机 SayType 扫码配置'
    $title.Font = New-Object System.Drawing.Font 'Microsoft YaHei UI', 12, ([System.Drawing.FontStyle]::Bold)
    $title.ForeColor = [System.Drawing.Color]::FromArgb(16, 24, 40)
    $title.AutoSize = $true
    $title.Location = New-Object System.Drawing.Point(24, 16)
    $form.Controls.Add($title)

    $pinLabel = New-Object System.Windows.Forms.Label
    $pinLabel.Text = 'PIN  ' + $j.pin
    $pinLabel.Font = New-Object System.Drawing.Font 'Consolas', 18, ([System.Drawing.FontStyle]::Bold)
    $pinLabel.ForeColor = [System.Drawing.Color]::FromArgb(26, 115, 232)
    $pinLabel.AutoSize = $true
    $pinLabel.Location = New-Object System.Drawing.Point(24, 48)
    $form.Controls.Add($pinLabel)

    $pic = New-Object System.Windows.Forms.PictureBox
    $pic.Size = New-Object System.Drawing.Size(312, 312)
    $pic.Location = New-Object System.Drawing.Point(24, 92)
    $pic.SizeMode = 'Zoom'
    $pic.BackColor = [System.Drawing.Color]::White
    $pic.BorderStyle = 'FixedSingle'
    $img = New-Object System.Drawing.Bitmap($qrPath)
    $pic.Image = $img
    $form.Controls.Add($pic)

    $addr = New-Object System.Windows.Forms.Label
    $addr.Text = $addrText
    $addr.Font = New-Object System.Drawing.Font 'Consolas', 9
    $addr.ForeColor = [System.Drawing.Color]::FromArgb(71, 84, 103)
    $addr.Location = New-Object System.Drawing.Point(24, 416)
    $addr.Size = New-Object System.Drawing.Size(312, 20)
    $form.Controls.Add($addr)

    $hint = New-Object System.Windows.Forms.Label
    $hint.Text = '手机与电脑需在同一局域网'
    $hint.Font = New-Object System.Drawing.Font 'Microsoft YaHei UI', 8
    $hint.ForeColor = [System.Drawing.Color]::FromArgb(148, 163, 184)
    $hint.Location = New-Object System.Drawing.Point(24, 438)
    $hint.AutoSize = $true
    $form.Controls.Add($hint)

    $form.Add_Shown({ $form.Activate() })
    [void]$form.ShowDialog()
    if ($pic.Image) { $pic.Image.Dispose() }
    $form.Dispose()
}

function Show-Pin {
    Show-Qr
}

function Start-NodeService {
    New-Item -ItemType Directory -Force -Path (Join-Path $Root 'runtime') | Out-Null
    Remove-Item $StatusFile -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $Root 'runtime\config-qr.png') -ErrorAction SilentlyContinue
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

# --- firewall (first run) ---
$ruleOutput = netsh advfirewall firewall show rule name="phone-type-8787" 2>$null | Out-String
if ($ruleOutput -notmatch 'phone-type-8787') {
    try {
        $proc = Start-Process powershell -Verb RunAs -PassThru -WindowStyle Hidden -ArgumentList `
            '-NoProfile', '-Command', "netsh advfirewall firewall add rule name='phone-type-8787' dir=in action=allow protocol=TCP localport=8787"
        $proc.WaitForExit()
    } catch { }
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
