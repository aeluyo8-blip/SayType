# Stop SayType background service via runtime/status.json
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
$StatusFile = Join-Path $Root 'runtime\status.json'
if (-not (Test-Path $StatusFile)) {
    Write-Host "[i] 未找到 status.json，服务可能未在运行"
    exit 0
}
try {
    $j = Get-Content -Raw $StatusFile | ConvertFrom-Json
    if ($j.pid) {
        taskkill /PID $j.pid /F 2>$null | Out-Null
        Write-Host "[+] 已停止服务 PID $($j.pid)" -ForegroundColor Green
    } else {
        Write-Host "[!] status.json 无 pid"
    }
} catch {
    Write-Host "[!] 读取 status.json 失败: $_" -ForegroundColor Red
}
Remove-Item $StatusFile -ErrorAction SilentlyContinue
