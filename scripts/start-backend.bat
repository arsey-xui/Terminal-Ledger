@echo off
rem start-backend.bat - one-click launcher for backend (default: dev mode, H2)
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-backend.ps1" %*
echo.
echo [start-backend] Done. Press any key to close this window.
pause >nul
endlocal
