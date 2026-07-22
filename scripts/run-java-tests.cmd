@echo off
setlocal
cd /d "%~dp0..\control-plane"
call mvn test

