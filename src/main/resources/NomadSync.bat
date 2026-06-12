@echo off
if "%1"=="" (
    echo Usage: NomadSync.bat [pull^|push^|autosave]
    pause
    exit /b 1
)
if "%2"=="" (
    echo No properties file specified, targeting to develop
    java -jar NomadSync.jar %1 config.dev.properties
    pause
    exit /b 0
)
echo Targeting to required properties file: %2
java -jar NomadSync.jar %1 %2
pause
exit /b 0

