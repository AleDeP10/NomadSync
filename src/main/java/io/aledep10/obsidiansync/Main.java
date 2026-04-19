package io.aledep10.obsidiansync;

import io.aledep10.obsidiansync.service.GitService;
import io.aledep10.obsidiansync.service.LogService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class Main {
    static void main(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("Usage: java ObsidianSync.jar [pull|push|autosave] <properties_file>");
            System.exit(1);
        }

        File file = new File(args[1]);
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(file));
        } catch (IOException e) {
            System.err.println("Unable to load file " + args[1]);
            System.exit(1);
        }

        LogService logService = new LogService(properties);
        GitService gitService = new GitService(properties, logService);
        try {
            switch (args[0]) {
                case "pull" -> {
                    logService.info("-> Performing pull");
                    gitService.pull();
                    logService.info("-> pull completed");
                }

                case "push" -> {
                    logService.info("-> Performing push");
                    gitService.push();
                    logService.info("-> push completed");
                }

                case "autosave" -> {
                    logService.info("-> Performing autosave");
                    gitService.autosave();
                    logService.info("-> autosave completed");
                }

                default -> {
                    logService.error("Usage: java ObsidianSync.jar [pull|push|autosave] <properties_file>");
                    System.exit(1);
                }
            }
        } catch (IOException e) {
            logService.error("I/O error while performing "+args[0]+": " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            logService.error("Operation interrupted while performing "+args[0]+": " + e.getMessage());
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

}
