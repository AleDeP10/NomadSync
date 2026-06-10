package io.aledep10.nomadsync.hook;

import io.aledep10.nomadsync.orchestrator.SyncEvent;

/**
 * Abstraction for user-facing failure notifications.
 *
 * <p>Follows the Dependency Inversion Principle — SyncOrchestrator depends on this
 * interface, not on any concrete implementation. The default implementation logs
 * the failure via LogService. A future implementation will show a system tray balloon.</p>
 */
public interface NotificationHook {

    /**
     * Called when a sync event fails definitively after all retry attempts.
     *
     * @param event the event that could not be processed
     * @param message human-readable description of the failure
     */
    void onFailure(SyncEvent event, String message);
}
