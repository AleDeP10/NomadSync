@echo off
REM Usage: NomadSyncCommit.bat [config.properties] <vaultId>
REM
REM Opens a text editor for a commit message, then performs a local-only
REM commit on the specified vault. Requires vaultId — a manual commit
REM without an explicit target is intentionally not supported.

if "%2"=="" (
    echo Usage: NomadSyncCommit.bat [config.properties] ^<vaultId^>
    echo vaultId is required for manual commits.
    pause
    exit /b 1
)

set CONFIG=%1
set VAULTID=%2
set TMPFILE=%TEMP%\nomadsync-commit-%RANDOM%.txt

REM Create empty message file and open default editor
type nul > "%TMPFILE%"
if defined EDITOR (
    %EDITOR% "%TMPFILE%"
) else (
    notepad "%TMPFILE%"
)

REM Check if the file has any non-whitespace content
for %%A in ("%TMPFILE%") do set SIZE=%%~zA
if %SIZE%==0 (
    echo Empty commit message — aborting, no commit created.
    del "%TMPFILE%"
    pause
    exit /b 0
)

call NomadSync.bat commit "%CONFIG%" "%VAULTID%" "%TMPFILE%"
del "%TMPFILE%"
