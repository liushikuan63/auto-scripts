@echo off
REM ================================================================
REM  FarmBot 统一构建脚本 — 单元测试 + Debug/Release 打包
REM  用法:
REM   build_all.bat test                 # 只跑单元测试
REM   build_all.bat debug                # 只编译 debug apk
REM   build_all.bat release              # 只编译 release apk (需要签名)
REM   build_all.bat all                  # 测试 + debug + release（默认）
REM ================================================================
setlocal

set TASK=%1
if "%TASK%"=="" set TASK=all

REM 尽量使用项目内置 fake-jdk21，若不存在则回退环境变量
if exist "%~dp0fake-jdk21\bin\java.exe" (
    set "JAVA_HOME=%~dp0fake-jdk21"
)
if not "%JAVA_HOME%"=="" (
    echo [INFO] 使用 JAVA_HOME=%JAVA_HOME%
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

REM 如果 local.properties 配置了 sdk.dir，Gradle 会自动读取；无需强制覆盖 ANDROID_HOME
set GRADLE_OPTS=-Xmx4096m -Dfile.encoding=UTF-8 -Dorg.gradle.jvmargs=-Xmx4096m

set "GRADLEW=%~dp0gradlew.bat"
if not exist "%GRADLEW%" (
    echo [ERROR] gradlew.bat 不存在，请在项目根目录运行此脚本
    exit /b 1
)

echo.
echo ================================================================
echo  FarmBot Build Script - task=%TASK%
echo ================================================================
echo.

if "%TASK%"=="test" goto RUN_TEST
if "%TASK%"=="debug" goto BUILD_DEBUG
if "%TASK%"=="release" goto BUILD_RELEASE
if "%TASK%"=="all" goto RUN_TEST

echo [ERROR] 未知任务: %TASK%，使用 test / debug / release / all
exit /b 2

:RUN_TEST
echo ===== Running Unit Tests =====
call "%GRADLEW%" :app:testDebugUnitTest --no-daemon --console=plain
set "TEST_EXIT=%ERRORLEVEL%"
echo TEST_EXIT_CODE=%TEST_EXIT%
if not "%TASK%"=="all" exit /b %TEST_EXIT%
if not "%TEST_EXIT%"=="0" (
    echo [WARN] 单元测试失败，仍尝试构建 APK 以便排查
)

:BUILD_DEBUG
echo.
echo ===== Building Debug APK =====
call "%GRADLEW%" :app:assembleDebug --no-daemon --console=plain
set "DBG_EXIT=%ERRORLEVEL%"
echo DEBUG_BUILD_EXIT_CODE=%DBG_EXIT%
if "%TASK%"=="debug" exit /b %DBG_EXIT%
if not "%DBG_EXIT%"=="0" (
    echo [ERROR] Debug 构建失败，Release 将跳过
    exit /b %DBG_EXIT%
)

:BUILD_RELEASE
echo.
echo ===== Building Release APK =====
call "%GRADLEW%" :app:assembleRelease --no-daemon --console=plain
set "REL_EXIT=%ERRORLEVEL%"
echo RELEASE_BUILD_EXIT_CODE=%REL_EXIT%
if "%TASK%"=="release" exit /b %REL_EXIT%

echo.
echo ================================================================
echo  构建完成
echo   Debug APK:   app\build\outputs\apk\debug\app-debug.apk
echo   Release APK: app\build\outputs\apk\release\app-release.apk
echo ================================================================
endlocal
exit /b %REL_EXIT%
