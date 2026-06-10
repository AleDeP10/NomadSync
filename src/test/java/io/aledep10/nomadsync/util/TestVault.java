package io.aledep10.nomadsync.util;

import java.nio.file.Path;

public record TestVault(
        String timestamp,
        Path rootPath,
        Path vaultPath,
        Path gitignoreFilePath,
        Path logFilePath,
        Path backupPath,
        Path conflictsPath
) {}
