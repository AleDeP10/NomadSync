package io.aledep10.nomadSync.orchestrator;

import io.aledep10.nomadSync.service.LogService;

import java.util.Iterator;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Thread-safe priority queue for {@link SyncEvent} objects.
 *
 * <p>Events are consumed in priority order — lower {@link EventType#getPriority()}
 * values are processed first. When two events of the same type are published,
 * only the most recent one is retained (latest-wins deduplication).</p>
 *
 * <p>Publish operations are {@code synchronized} to guarantee atomicity of the
 * check-remove-insert sequence. Consume delegates to {@link PriorityBlockingQueue#take()},
 * which is already thread-safe and blocks until an event is available.</p>
 */
public class SyncEventQueue {

    private final PriorityBlockingQueue<SyncEvent> queue;
    private final LogService logService;

    /**
     * Constructs a SyncEventQueue with an initial capacity of 10.
     *
     * @param logService shared logging service
     */
    public SyncEventQueue(LogService logService) {
        this.logService = logService;
        this.queue = new PriorityBlockingQueue<>(10);
    }

    /**
     * Publishes a {@link SyncEvent} to the queue applying latest-wins deduplication.
     *
     * <p>If an event of the same type is already queued:</p>
     * <ul>
     *   <li>If the incoming event is newer — the existing one is replaced.</li>
     *   <li>If the incoming event is older or equal — it is discarded.</li>
     * </ul>
     * <p>If no event of the same type is present, the event is enqueued directly.</p>
     *
     * <p>The entire check-remove-insert sequence is {@code synchronized} to prevent
     * race conditions between concurrent publishers.</p>
     *
     * @param event the event to publish
     */
    public synchronized void publish(SyncEvent event) {
        Iterator<SyncEvent> it = queue.iterator();
        while (it.hasNext()) {
            SyncEvent existing = it.next();
            if (existing.getType() == event.getType()) {
                if (event.getTimestamp() > existing.getTimestamp()) {
                    // incoming event is newer — replace
                    it.remove();
                    queue.add(event);
                    logService.info("Queue: replaced " + existing + " with " + event);
                } else {
                    // incoming event is older — discard
                    logService.info("Queue: discarded outdated " + event);
                }
                return; // duplicate found — either replaced or discarded, stop
            }
        }
        // no duplicate found — enqueue
        queue.add(event);
        logService.info("Queue: published " + event);
    }

    /**
     * Returns the number of events currently in the queue.
     *
     * <p>Synchronized to ensure a consistent read when called concurrently
     * with {@link #publish(SyncEvent)}.</p>
     *
     * @return current queue size
     */
    public synchronized int size() {
        return queue.size();
    }

    /**
     * Retrieves and removes the highest-priority event from the queue,
     * blocking until one becomes available.
     *
     * <p>Does not require external synchronization — {@link PriorityBlockingQueue#take()}
     * is atomic and thread-safe by itself. Holding a lock here would prevent
     * {@link #publish(SyncEvent)} from inserting events while the worker waits,
     * causing a deadlock.</p>
     *
     * @return the highest-priority event
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public SyncEvent consume() throws InterruptedException {
        return queue.take();
    }
}