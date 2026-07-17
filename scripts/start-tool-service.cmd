@echo off
setlocal
title Agent Video Pipeline - Python Tool Service
cd /d "%~dp0..\tool-service"
set "PYTHON=C:\software\Anaconda\envs\agent-video-pipeline\python.exe"
if not exist "%PYTHON%" (
  echo Conda environment not found: %PYTHON%
  goto :error
)

netstat -ano | findstr /R /C:":8090 .*LISTENING" >nul
if not errorlevel 1 (
  echo Port 8090 is already in use.
  echo The Tool Service may already be running. Open http://127.0.0.1:8090/api/v1/health to check it.
  goto :error
)

echo Starting Python Tool Service on http://127.0.0.1:8090
echo Press Ctrl+C to stop it.
echo.
"%PYTHON%" -m uvicorn app.main:app --host 127.0.0.1 --port 8090
if errorlevel 1 goto :error
exit /b 0

:error
echo.
echo Tool Service failed to start. Review the message above.
pause
exit /b 1
