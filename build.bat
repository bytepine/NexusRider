@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

echo ============================================
echo   NexusMCP - Rider Plugin Build
echo ============================================
echo.

cd /d "%~dp0"

echo [1/2] Building plugin...
call gradlew.bat clean buildPlugin --console=plain
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Build failed! See output above for details.
    pause
    exit /b 1
)

:: ── 移动产物到 release/ ──────────────────────────────────
if not exist release mkdir release
for %%f in (build\distributions\*.zip) do (
    copy /y "%%f" "release\" >nul
    echo.
    echo [2/2] Build successful!
    echo.
    echo Output: %cd%\release\%%~nxf
)
echo.
echo Install: Rider → Settings → Plugins → ⚙ → Install Plugin from Disk...
echo.
pause
