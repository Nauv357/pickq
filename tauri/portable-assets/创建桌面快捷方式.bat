@echo off
rem 为「拾题」在当前目录创建桌面快捷方式
powershell -NoProfile -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut([Environment]::GetFolderPath('Desktop') + '\拾题.lnk'); $s.TargetPath = '%~dp0tiku-desktop.exe'; $s.WorkingDirectory = '%~dp0'; $s.IconLocation = '%~dp0tiku-desktop.exe,0'; $s.Save()"
echo 已创建桌面快捷方式「拾题」
pause
