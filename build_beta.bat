@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

echo ============================================
echo   NexusMCP Rider Plugin - Beta Build
echo ============================================
echo.

cd /d "%~dp0"

:: ── 1. 读取当前版本 ──────────────────────────────────────
set /p CURRENT_VERSION=<VERSION
set CURRENT_VERSION=%CURRENT_VERSION: =%
echo Current VERSION : %CURRENT_VERSION%

:: 解析 major.minor.patch（strip 可能的 -beta/-rc 后缀）
for /f "tokens=1,2,3 delims=." %%a in ("%CURRENT_VERSION%") do (
    set MAJOR=%%a
    set MINOR=%%b
    set PATCH=%%c
)
for /f "tokens=1 delims=-" %%x in ("%PATCH%") do set PATCH=%%x

set /a NEXT_PATCH=%PATCH%+1
set NEXT_VERSION=%MAJOR%.%MINOR%.%NEXT_PATCH%-beta

echo Next beta version: %NEXT_VERSION%
echo.

:: ── 2. 构建（通过 -P 注入版本，不改源码文件）────────────
echo [1/2] Building plugin (version: %NEXT_VERSION%)...
call gradlew.bat clean buildPlugin -PpluginVersion=%NEXT_VERSION% --console=plain
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Build failed! See output above for details.
    pause
    exit /b 1
)

:: ── 3. 移动产物到 release/ ──────────────────────────────
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
