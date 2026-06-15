@echo off
if "%1"=="" (
    echo Usage: NomadSync.bat [pull^|push^|sync^|autosave^|commit] [config.properties] [vaultId] [messageFile]
    pause
    exit /b 1
)
if "%2"=="" (
    echo No properties file specified, targeting to develop
    java -jar NomadSync.jar %1 config.dev.properties %3 %4
    pause
    exit /b 0
)
echo Targeting to required properties file: %2
java -jar NomadSync.jar %1 %2 %3 %4
pause
exit /b 0
