package io.aledep10.obsidiansync.orchestrator;

public class SyncOrchestrator {
    /*
PRIVATE METHOD execute(event)
    TRY
        SWITCH event.type

            PULL_LOGON
                gitService.stash()
                gitService.pull()          // remoto — fallibile
                gitService.stashPop()

            PUSH_MANUAL
            PUSH_LOGOFF
                SE gitService.hasChanges() // diff --quiet
                    gitService.commitLocal()
                gitService.push()          // remoto — fallibile

            AUTOSAVE
                SE gitService.hasChanges()
                    gitService.commitLocal() // locale — non fallibile per rete

    CATCH NetworkException
        // solo PULL_LOGON, PUSH_MANUAL, PUSH_LOGOFF arrivano qui
        SE event.retryCount < 3
            event.retryCount++
            event.retryDelay ← 30s * 2^(retryCount-1)
            schedula re-publish(event) dopo retryDelay
        ALTRIMENTI
            logService.log("FAILED after 3 retries: " + event.type)
            notificationHook.notify(event)

    CATCH GitException
        // errori locali (es. merge conflict, stash conflict)
        // non si riprova — si logga e si notifica sempre
        logService.log("GIT ERROR: " + event.type)
        notificationHook.notify(event)
    */
}
