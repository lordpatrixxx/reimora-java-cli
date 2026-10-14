package reimora;

import java.nio.file.*;
import java.time.*;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        Path root = Path.of("").toAbsolutePath();
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Reimora terminal edition\nRun: java -cp build reimora.Main [--workspace <folder>]\nA workspace contains data/ and reports/. Requires JDK 17+."); return;
        }
        if (args.length == 2 && args[0].equals("--workspace")) root = Path.of(args[1]).toAbsolutePath();
        else if (args.length != 0) { System.err.println("Usage: reimora.Main [--workspace <folder>]"); System.exit(2); }
        Path data = root.resolve("data");
        try (FileRepository repository = new FileRepository(data)) {
            Security security = new Security(data);
            ReimoraService service = new ReimoraService(repository, security, Clock.system(ZoneId.of("Asia/Kolkata")));
            DocumentStorage documents = new DocumentStorage(data.resolve("receipts"));
            Reports reports = new Reports(service, root.resolve("reports"));
            try (NotificationWorker worker = new NotificationWorker(repository, new LocalMailboxDelivery(data.resolve("mailbox")))) {
                worker.start();
                try { new ConsoleApp(service, documents, reports, worker, root).run(); }
                catch (Terminal.EndOfInput e) { System.out.println("\nInput closed. Saving local notifications and exiting."); }
            }
            System.out.println("Goodbye. Records are saved locally.");
        } catch (Exception e) { System.err.println("Reimora could not continue: " + e.getMessage()); System.exit(1); }
    }
}
