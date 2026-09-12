@echo off
chcp 65001 >nul
cd /d "%~dp0"
set STATUS=%~dp0runtime\status.json
if not exist "%STATUS%" (
  echo [i] 未找到 status.json，服务可能未在运行。请先双击 启动服务.bat
  pause
  exit /b 1
)
powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0scripts\show-pin.ps1"
