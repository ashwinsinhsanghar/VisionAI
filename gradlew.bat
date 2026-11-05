@echo off
setlocal
set DIR=%~dp0
for /f "tokens=2 delims=-" %%v in ('findstr "distributionUrl" "%DIR%gradle\wrapper\gradle-wrapper.properties"') do set GRADLE_VERSION=%%v
if "%GRADLE_VERSION%"=="" set GRADLE_VERSION=8.7
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
    gradle %*
    exit /b %ERRORLEVEL%
)
echo Please install Gradle %GRADLE_VERSION% manually from https://gradle.org/releases/ and run "gradle %*"
exit /b 1
