# phone-type 服务启动脚本（小白版入口，由 启动服务.bat 调用）
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  phone-type 服务" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 检测 Node.js
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "[!] 没有检测到 Node.js（需要 Node 20 或更高版本）" -ForegroundColor Yellow
    Write-Host "[i] 正在为你打开下载页面，安装完成后重新双击 启动服务.bat 即可"
    Start-Process "https://nodejs.org/zh-cn/download"
    Read-Host "按回车键关闭窗口"
    exit 1
}

# 2. 首次运行自动安装依赖
if (-not (Test-Path "node_modules\ws")) {
    Write-Host "[..] 首次运行，正在安装依赖（几秒钟）..."
    npm install --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[!] 依赖安装失败，请检查网络后重试" -ForegroundColor Red
        Read-Host "按回车键关闭窗口"
        exit 1
    }
    Write-Host "[+] 依赖安装完成"
    Write-Host ""
}

# 3. 防火墙自检：手机能否连上的关键，没放行就自动提权添加
$ruleOutput = netsh advfirewall firewall show rule name="phone-type-8787" 2>$null | Out-String
if ($ruleOutput -notmatch 'phone-type-8787') {
    Write-Host "[i] 首次运行需要放行防火墙，请在弹出的窗口点【是】..."
    $proc = Start-Process powershell -Verb RunAs -PassThru -WindowStyle Hidden -ArgumentList `
        '-NoProfile', '-Command', "netsh advfirewall firewall add rule name='phone-type-8787' dir=in action=allow protocol=TCP localport=8787"
    $proc.WaitForExit()
    if ($proc.ExitCode -eq 0) {
        Write-Host "[+] 防火墙已放行 8787 端口" -ForegroundColor Green
    } else {
        Write-Host "[i] 未放行防火墙。如果手机一直连不上，请右键 启动服务.bat 选择【以管理员身份运行】一次" -ForegroundColor Yellow
    }
    Write-Host ""
}

# 4. 启动服务：控制台显示的 PIN 就是手机要填的 6 位数字
Write-Host "正在启动服务。下方显示的 PIN 就是手机上要填的 6 位数字。" -ForegroundColor Cyan
Write-Host "停止服务：按 Ctrl+C 或直接关闭本窗口。" -ForegroundColor DarkGray
Write-Host ""
node server\index.mjs

Write-Host ""
Write-Host "服务已退出，按回车键关闭窗口"
Read-Host
