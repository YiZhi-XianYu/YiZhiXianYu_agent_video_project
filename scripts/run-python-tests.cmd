@echo off
setlocal
cd /d "%~dp0..\tool-service"
if not exist "..\runtime\tmp" mkdir "..\runtime\tmp"
set "TEMP=%CD%\..\runtime\tmp"
set "TMP=%TEMP%"
python -m pytest
