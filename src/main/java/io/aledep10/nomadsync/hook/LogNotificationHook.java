package io.aledep10.nomadsync.hook;

import io.aledep10.nomadsync.orchestrator.SyncEvent;
import io.aledep10.nomadsync.service.LogService;

/**
 * Default NotificationHook implementation — writes failures to the log file.
 * Will be replaced by a tray icon implementation in a future milestone.
 */
public class LogNotificationHook implements NotificationHook {

    private final LogService logService;

    public LogNotificationHook(LogService logService) {
        this.logService = logService;
    }

    @Override
    public void onFailure(SyncEvent event, String message) {
        logService.error("FAILED [" + event.getType() + "]: " + message);
    }
}
