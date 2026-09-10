# Optional standalone inject helper (kept for manual tests).
# Usage: echo <base64-utf8-text> | powershell -File inject.ps1
$ErrorActionPreference = 'Stop'
$b64 = [Console]::In.ReadToEnd().Trim()
$bytes = [System.Convert]::FromBase64String($b64)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Clipboard]::SetText($text)
Start-Sleep -Milliseconds 40
[System.Windows.Forms.SendKeys]::SendWait('^v')
Write-Output 'OK'
