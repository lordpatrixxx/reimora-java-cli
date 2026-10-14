package reimora;

import java.util.concurrent.*;
import reimora.Models.*;
import static reimora.Types.MailStatus;

/** Runnable demonstrates real background work while the terminal remains usable. */
public final class NotificationWorker implements Runnable, AutoCloseable {
    private final Repository repository;
    private final NotificationDelivery delivery;
    private ScheduledExecutorService executor;
    NotificationWorker(Repository repository, NotificationDelivery delivery) { this.repository = repository; this.delivery = delivery; }
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "reimora-notifications"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this, 0, 2, TimeUnit.SECONDS);
    }
    @Override public synchronized void run() {
        try {
            State snapshot = repository.snapshot();
            for (Notification n : snapshot.notifications.values()) {
                if (n.status() != MailStatus.QUEUED && !(n.status() == MailStatus.FAILED && n.attempts() < 3)) continue;
                boolean success = false; String error = "";
                try { delivery.deliver(n, snapshot.users.get(n.userId())); success = true; }
                catch (Exception e) { error = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                final boolean result = success; final String failure = error;
                repository.update(s -> { Notification live = s.notifications.get(n.id()); live.result(result, failure); return null; });
            }
        } catch (Exception e) { System.err.println("[Notification worker] " + e.getMessage()); }
    }
    @Override public void close() {
        if (executor != null) {
            executor.shutdown();
            try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
            catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        run();
    }
}
