@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   Starting Personal Accounting (Desktop)
echo   First run may download dependencies...
echo   Please wait.
echo ============================================
echo.
call mvn -s "..\maven-settings-local.xml" javafx:run
echo.
echo The app has closed. Press any key to exit.
pause >nul
