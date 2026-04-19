package io.aledep10.obsidiansync.orchestrator;


import java.util.Iterator;
import java.util.concurrent.PriorityBlockingQueue;

public class SyncEventQueue {
    // struttura interna: PriorityQueue thread-safe
    private PriorityBlockingQueue<SyncEvent> queue;

    public SyncEventQueue() {
        queue = new PriorityBlockingQueue<>(5);
    }

    public void publish(SyncEvent event) {
        // cerca in queue un evento dello stesso tipo
        for (SyncEvent e : queue) {
            if (e.getType() == event.getType()) {
                if (event.getTimestamp() > e.getTimestamp()) {

                }
            }
        }

        SyncEvent e = null;
        Iterator<SyncEvent> eventsIt = queue.iterator();
        while (eventsIt.hasNext()) {
            e = eventsIt.next();
            // SE trovato
            if (e.getType() == event.getType()) {
                // SE event.timestamp > esistente.timestamp
                if (event.getTimestamp() > e.getTimestamp()) {
                    // rimuovi esistente
                    eventsIt.remove();
                    // inserisci event          // latest wins
                    queue.add(event);
                }   // altrimenti scarta il nuovo
            } else {
                // inserisci event
                queue.add(event);
            }

        }

    }

    public SyncEvent consume() {
        /*
            bloccante: attende finché la coda non è vuota
            restituisce l'evento a priorità più alta
         */
        return null;
    }
}
