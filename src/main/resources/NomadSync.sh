#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: NomadSync.sh [pull|push|sync|autosave|commit] [config.properties] [vaultId] [messageFile]"
    exit 1
fi
if [ -z "$2" ]; then
    echo "No properties file specified, targeting to develop"
    java -jar NomadSync.jar "$1" config.dev.properties "$3" "$4"
    exit 0
fi
echo "Targeting to required properties file: $2"
java -jar NomadSync.jar "$1" "$2" "$3" "$4"
exit 0
