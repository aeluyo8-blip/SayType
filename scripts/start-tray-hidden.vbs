' Launch SayType tray with no console window
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("Wscript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
' scripts\ -> project root
root = fso.GetParentFolderName(dir)
sh.CurrentDirectory = root
cmd = "powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File """ & dir & "\saytype-tray.ps1"""
sh.Run cmd, 0, False
