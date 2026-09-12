@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem Launch tray via VBS so no black console stays open
wscript.exe "%~dp0scripts\start-tray-hidden.vbs"
