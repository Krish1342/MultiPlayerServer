@echo off
setlocal EnableExtensions EnableDelayedExpansion

if /I "%~1"=="--setup" goto :setup
if /I "%~1"=="setup" goto :setup
if /I "%~1"=="--help" goto :help
if /I "%~1"=="help" goto :help

call :ensureCommand java "Java (JDK 21+)"
if errorlevel 1 exit /b 1

call :ensureCommand mvn "Apache Maven"
if errorlevel 1 exit /b 1

echo Starting Multiplayer server...
mvn -DskipTests exec:java -Dexec.mainClass="com.multiplayer.server.network.GameServer"
exit /b %errorlevel%

:setup
echo Running one-time persistent environment setup...

set "TARGET_JAVA_HOME="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "TARGET_JAVA_HOME=%JAVA_HOME%"

if not defined TARGET_JAVA_HOME (
    for /f "delims=" %%I in ('where java 2^>nul') do (
        for %%J in ("%%~dpI..") do set "TARGET_JAVA_HOME=%%~fJ"
        goto :javaFound
    )
)
:javaFound

if not defined TARGET_JAVA_HOME (
    echo Could not detect JAVA_HOME automatically.
    echo Install JDK 21 and set JAVA_HOME, then re-run: start_server.bat --setup
    exit /b 1
)

set "TARGET_MAVEN_HOME="
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "TARGET_MAVEN_HOME=%MAVEN_HOME%"

if not defined TARGET_MAVEN_HOME (
    for /f "delims=" %%I in ('where mvn 2^>nul') do (
        for %%J in ("%%~dpI..") do set "TARGET_MAVEN_HOME=%%~fJ"
        goto :mavenFound
    )
)
:mavenFound

if not defined TARGET_MAVEN_HOME (
    echo Could not detect MAVEN_HOME automatically.
    echo Install Apache Maven and add mvn to PATH, then re-run: start_server.bat --setup
    exit /b 1
)

echo Setting user environment variables permanently...
setx JAVA_HOME "%TARGET_JAVA_HOME%" >nul
if errorlevel 1 (
    echo Failed to persist JAVA_HOME.
    exit /b 1
)

setx MAVEN_HOME "%TARGET_MAVEN_HOME%" >nul
if errorlevel 1 (
    echo Failed to persist MAVEN_HOME.
    exit /b 1
)

set "USER_PATH="
for /f "skip=2 tokens=2,*" %%A in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "USER_PATH=%%B"

set "NEW_USER_PATH=!USER_PATH!"
if not defined NEW_USER_PATH set "NEW_USER_PATH="

set "JAVA_BIN=%TARGET_JAVA_HOME%\bin"
set "MAVEN_BIN=%TARGET_MAVEN_HOME%\bin"

echo ;!NEW_USER_PATH!; | findstr /I /C:";%JAVA_BIN%;" >nul
if errorlevel 1 (
    if defined NEW_USER_PATH (
        set "NEW_USER_PATH=!NEW_USER_PATH!;%JAVA_BIN%"
    ) else (
        set "NEW_USER_PATH=%JAVA_BIN%"
    )
)

echo ;!NEW_USER_PATH!; | findstr /I /C:";%MAVEN_BIN%;" >nul
if errorlevel 1 (
    if defined NEW_USER_PATH (
        set "NEW_USER_PATH=!NEW_USER_PATH!;%MAVEN_BIN%"
    ) else (
        set "NEW_USER_PATH=%MAVEN_BIN%"
    )
)

reg add "HKCU\Environment" /v Path /t REG_EXPAND_SZ /d "!NEW_USER_PATH!" /f >nul
if errorlevel 1 (
    echo Failed to persist user PATH in registry.
    exit /b 1
)

set "JAVA_HOME=%TARGET_JAVA_HOME%"
set "MAVEN_HOME=%TARGET_MAVEN_HOME%"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

echo Setup complete.
echo JAVA_HOME=%JAVA_HOME%
echo MAVEN_HOME=%MAVEN_HOME%
echo Open a new terminal to use persisted variables automatically.
echo In PowerShell, run: .\start_server.bat
exit /b 0

:ensureCommand
where %~1 >nul 2>nul
if errorlevel 1 (
    echo Missing %~2 in PATH.
    echo Run start_server.bat --setup after installing required tools.
    exit /b 1
)
exit /b 0

:help
echo Usage:
echo   .\start_server.bat            ^(PowerShell: start server if env is already configured^)
echo   .\start_server.bat --setup    ^(PowerShell: persist JAVA_HOME, MAVEN_HOME, and user PATH^)
exit /b 0
