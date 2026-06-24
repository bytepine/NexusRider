@echo off
setlocal

echo ============================================
echo   NexusMCP - Rider Plugin Build
echo ============================================
echo.

cd /d "%~dp0"

echo [1/2] Building plugin...
call gradlew.bat clean buildPlugin
if %ERRORLEVEL% neq 0 (
    echo.
    echo BUILD FAILED!
    exit /b 1
)

echo.
echo [2/2] Build successful!
echo.

:: Find the output zip
for %%f in (build\distributions\*.zip) do (
    echo Output: %cd%\%%f
)
echo.
