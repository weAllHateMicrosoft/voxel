@echo off
:: ─────────────────────────────────────────────────────────────────────────────
::  play-windows.bat  —  launches DESCENT (Windows)
::  Just double-click this file to play!
:: ─────────────────────────────────────────────────────────────────────────────
setlocal EnableDelayedExpansion

set REPO=yrdsb-peths/Final-dominic-arron
set JAR_URL=https://github.com/%REPO%/releases/latest/download/game.jar
set JAR=%~dp0game.jar
set JAVA_URL=https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x64&package=jdk

:: ── 1. Check Java ─────────────────────────────────────────────────────────────
where java >nul 2>&1
if %ERRORLEVEL% neq 0 goto :need_java

:: Extract major version (handles both "17.0.x" and legacy "1.8" formats)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_RAW=%%v
set JAVA_RAW=%JAVA_RAW:"=%
for /f "tokens=1 delims=." %%m in ("%JAVA_RAW%") do set JAVA_MAJOR=%%m
if "%JAVA_MAJOR%"=="1" for /f "tokens=2 delims=." %%m in ("%JAVA_RAW%") do set JAVA_MAJOR=%%m

if !JAVA_MAJOR! LSS 17 (
    echo.
    echo   Java !JAVA_MAJOR! is installed but Java 17+ is required.
    goto :need_java
)
goto :find_jar

:need_java
echo.
echo   Java 17 is required.
:: Try winget first (built into Windows 10 1809+ / Windows 11)
where winget >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo   Installing Java 17 via winget...
    winget install --id EclipseAdoptium.Temurin.17.JDK --accept-source-agreements --accept-package-agreements
    :: Refresh PATH so the new java is visible
    for /f "tokens=*" %%p in ('where java 2^>nul') do set JAVA_EXE=%%p
    if not "%JAVA_EXE%"=="" (
        echo   Java installed. Continuing...
        goto :find_jar
    )
)
echo   Opening the Java download page in your browser...
start "" "%JAVA_URL%"
echo   Install Java 17, then double-click this file again.
echo.
pause
exit /b 1

:: ── 2. Find / fetch the JAR ───────────────────────────────────────────────────
:find_jar
if exist "%JAR%" goto :launch

echo.
echo   Downloading game...
curl -fsSL -o "%JAR%.tmp" "%JAR_URL%"
if %ERRORLEVEL% equ 0 (
    move /y "%JAR%.tmp" "%JAR%" >nul
    echo   Download complete!
    goto :launch
)
del "%JAR%.tmp" 2>nul

:: Fallback: build from Gradle source (works when the repo is cloned)
if exist "%~dp0gradlew.bat" (
    echo   No download available -- building from source ^(takes ~30 s^)...
    cd /d "%~dp0"
    call gradlew.bat shadowJar --no-daemon -q
    if exist "%~dp0build\libs\game.jar" (
        copy /y "%~dp0build\libs\game.jar" "%JAR%" >nul
        echo   Build complete!
        goto :launch
    )
)

echo.
echo   Could not find or build the game.
echo   If you cloned the repo, try:  gradlew shadowJar
echo.
pause
exit /b 1

:: ── 3. Launch ─────────────────────────────────────────────────────────────────
:launch
echo   Launching DESCENT...
echo.
java -jar "%JAR%"
if %ERRORLEVEL% neq 0 pause
