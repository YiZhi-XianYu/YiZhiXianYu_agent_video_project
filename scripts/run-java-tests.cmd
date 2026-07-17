@echo off
setlocal
cd /d "%~dp0..\control-plane"
call "C:\software\IDEA\IntelliJ IDEA 2025.2.2\plugins\maven\lib\maven3\bin\mvn.cmd" test

