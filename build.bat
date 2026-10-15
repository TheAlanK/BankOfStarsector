@echo off
REM ============================================================
REM  Bank of Starsector - Build Script
REM  Runs build.ps1, which finds the optional mods' jars
REM  (Nexerelin, NexusUI, LunaLib, Console Commands) wherever
REM  a mod manager installed them. Arguments are passed on,
REM  e.g.  build.bat -NoInstall
REM ============================================================

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo *** BUILD FAILED ***
    pause
    exit /b 1
)
pause
