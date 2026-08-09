@echo off
REM ============================================================
REM  NetOps 2.0 - Generate / regenerate release signing keystore
REM  Usage: double-click this file (cwd becomes script dir)
REM  Passwords are entered at runtime, never stored in this file.
REM  Output: app/release-key.jks  +  keystore.properties (gitignored)
REM ============================================================
cd /d "%~dp0"

set KEYTOOL="C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
set KEYSTORE=app\release-key.jks
set ALIAS=netops
set DNAME=CN=Seven John, OU=NetOps, O=NetOps, C=CN

if not exist %KEYTOOL% (
  echo [ERROR] keytool not found: %KEYTOOL%
  echo         Check your Android Studio install path.
  pause
  exit /b 1
)

if exist %KEYSTORE% (
  echo [WARN] %KEYSTORE% already exists; it will be deleted and recreated.
  del /p %KEYSTORE%
)

set /p STOREPASS=Enter keystore password (storepass):
set /p KEYPASS=Enter key password (keypass):

%KEYTOOL% -genkeypair -v -keystore %KEYSTORE% -keyalg RSA -keysize 2048 -validity 10000 -alias %ALIAS% -dname "%DNAME%" -storepass %STOREPASS% -keypass %KEYPASS%

if errorlevel 1 (
  echo [ERROR] keytool failed. Keystore NOT created.
  pause
  exit /b 1
)

REM Write keystore.properties (gitignored) so Gradle assembleRelease can sign automatically
(
  echo # Release signing config (gitignored, never committed^)
  echo storeFile=app/release-key.jks
  echo storePassword=%STOREPASS%
  echo keyAlias=netops
  echo keyPassword=%KEYPASS%
) > keystore.properties

echo.
echo [OK] Keystore created: %KEYSTORE%
echo [OK] keystore.properties written (passwords filled in).
echo.
echo Verify DN with:
echo   keytool -list -v -keystore %KEYSTORE% -storepass %STOREPASS%
echo.
echo Next: Android Studio - Build ^> Generate Signed Bundle/APK - APK
pause
