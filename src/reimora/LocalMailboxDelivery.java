package reimora;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import reimora.Models.*;

/** Deterministic filenames make retries idempotent. No network email is sent. */
public final class LocalMailboxDelivery implements NotificationDelivery {
    private final Path root;
    public LocalMailboxDelivery(Path root) { this.root = root; }
    @Override public void deliver(Notification n, User recipient) throws IOException {
        Path box = root.resolve(recipient.id()); Files.createDirectories(box);
        String message = "REIMORA LOCAL MAILBOX - NOT AN EXTERNAL EMAIL\nTo: " + recipient.email() +
                "\nSubject: " + n.subject() + "\nCreated: " + n.created() + "\nEvent: " + n.id() + "\n\n" + n.body() + "\n";
        Path target = box.resolve(n.id() + ".txt"), temp = box.resolve(n.id() + ".tmp");
        Files.writeString(temp, message, StandardCharsets.UTF_8);
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
