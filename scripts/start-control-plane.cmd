@echo off
setlocal
title Agent Video Pipeline - Java Control Plane
cd /d "%~dp0..\control-plane"
if "%MYSQL_USER%"=="" set "MYSQL_USER=root"
if "%MYSQL_PASSWORD%"=="" (
  echo MYSQL_PASSWORD is not set for this terminal.
  set /p "MYSQL_PASSWORD=Enter MySQL password for %MYSQL_USER%: "
)
if "%MYSQL_PASSWORD%"=="" (
  echo MySQL password cannot be empty.
  goto :error
)

netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul
if not errorlevel 1 (
  echo Port 8080 is already in use.
  echo The Control Plane may already be running. Open http://127.0.0.1:8080 to check it.
  goto :error
)

if "%JAVA_HOME%"=="" (
  echo JAVA_HOME is not set. Maven will use the Java configured in your system PATH.
)

echo Starting Java Control Plane on http://127.0.0.1:8080
echo MySQL user: %MYSQL_USER%
echo Press Ctrl+C to stop it.
echo.
call mvn spring-boot:run
if errorlevel 1 goto :error
exit /b 0

:error
echo.
echo Control Plane failed to start. Review the message above.
pause
exit /b 1
