# Show PIN from runtime/status.json
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$StatusFile = Join-Path $Root 'runtime\status.json'
if (-not (Test-Path $StatusFile)) {
    Add-Type -AssemblyName PresentationFramework
    [void][System.Windows.MessageBox]::Show('未找到 status.json，服务可能未在运行','SayType','OK','Warning')
    exit 1
}
$j = Get-Content -Raw $StatusFile | ConvertFrom-Json
$addrs = @()
if ($j.addrs) { foreach ($a in $j.addrs) { $addrs += ("ws://{0}:{1}" -f $a, $j.port) } }
$addrText = if ($addrs.Count) { $addrs -join [Environment]::NewLine } else { '(no LAN IP)' }
$msg = "PIN: " + $j.pin + [Environment]::NewLine + [Environment]::NewLine + "地址:" + [Environment]::NewLine + $addrText + [Environment]::NewLine + [Environment]::NewLine + "PID: " + $j.pid
Add-Type -AssemblyName PresentationFramework
[void][System.Windows.MessageBox]::Show($msg, 'SayType PIN', 'OK', 'Information')
