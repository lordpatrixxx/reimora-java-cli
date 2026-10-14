package reimora;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import reimora.Models.*;
import static reimora.Types.*;

/** Terminal presentation only; business rules are enforced by ReimoraService. */
final class ConsoleApp {
    private final ReimoraService service;
    private final DocumentStorage documents;
    private final Reports reports;
    private final NotificationWorker worker;
    private final Terminal terminal = new Terminal();
    private final TextReceiptParser parser = new TextReceiptParser();
    private final Path root;
    private String actorId;
    ConsoleApp(ReimoraService service, DocumentStorage documents, Reports reports, NotificationWorker worker, Path root) {
        this.service = service; this.documents = documents; this.reports = reports; this.worker = worker; this.root = root;
    }
    void run() {
        System.out.println("\n" + "=".repeat(70) + "\n  REIMORA | Student reimbursement and advance settlement\n  Java terminal edition - offline, local storage\n" + "=".repeat(70));
        System.out.println("Local notifications only. No actual bank transfers, external emails or image AI.\nData folder: " + root.resolve("data"));
        while (service.needsSetup()) {
            System.out.println("\nFirst-run administrator setup: " + ReimoraService.ADMIN_EMAIL);
            try { service.setupAdmin(terminal.newPassword()); System.out.println("Administrator created. Please sign in."); }
            catch (DomainException e) { error(e); }
        }
        while (true) {
            System.out.println("\n1. Sign in\n2. Register and request access\n3. Help / project information\n0. Exit");
            try {
                switch (terminal.choice("Choose: ", 3)) {
                    case 0 -> { return; }
                    case 1 -> { actorId = service.login(terminal.line("Email: "), terminal.password("Password: ")); signedIn(); actorId = null; }
                    case 2 -> register();
                    case 3 -> help();
                }
            } catch (DomainException | InvalidPathException e) { error(e); }
        }
    }
    private void error(Exception e) { System.out.println("\n[Not completed] " + e.getMessage()); }
    private void help() {
        System.out.println("""
                Reimora is an Object-Oriented Programming Laboratory mini project.
                Pratik Deepak Phapale | 125B1F210 | IT SY Semester III Division B
                Faculty: Tanuja Patankar | PCCOE, Pune

                1. Register a Student or Approver account.
                2. The administrator approves its access request.
                3. Student saves a beneficiary, creates a claim, adds expenses/evidence,
                   optionally selects an advance, then submits.
                4. An assigned faculty approver reviews and approves/request changes/rejects.
                5. Administrator records reimbursement or advance return.

                IDs such as C00001 and E00002 appear in lists. Enter them at prompts.
                Blank filter searches show all records you are authorized to view.
                Use full paths for attachments (quotes around paths are accepted).
                Text receipts support Vendor:, Amount:, Date:, Reference:, Items:, Purpose:.
                Category suggestions are keyword rules, NOT AI or OCR.
                Local inbox messages are also saved under data/mailbox/<user ID>/.
                Only fictional examples are created by the demo-data option.
                """);
    }
    private void register() {
        String name = terminal.line("Full name: "), email = terminal.line("Email (local identity; no verification email): ");
        Role requested = terminal.pick("Requested role", new Role[]{Role.STUDENT, Role.APPROVER}); if (requested == null) return;
        String prn = terminal.line("PRN or faculty/staff ID: "), division = terminal.line("Division or designation: ");
        Branch branch = terminal.pick("PCCOE B.Tech branch", Branch.values()); if (branch == null) return;
        String reason = terminal.line("Reason for access: "), password = terminal.newPassword();
        String id = service.register(name, email, prn, division, branch, password, requested, reason);
        System.out.println("Registration successful: " + id + ". Access is pending; administrator has a local notification.");
    }
    private void signedIn() {
        while (true) {
            User actor = service.user(actorId);
            System.out.println("\n" + "-".repeat(70) + "\n" + actor.name() + " | " + actor.role() + " | " + actor.id() + " | " + actor.branch());
            long unread = service.inbox(actorId).stream().filter(n -> !n.read()).count();
            System.out.println("Unread notifications: " + unread);
            if (actor.role() == Role.PENDING) { if (!pendingMenu()) return; continue; }
            System.out.println("1. Dashboard\n2. List/search claims\n3. Create claim\n4. Open/manage claim\n5. Beneficiary profile\n6. Advances\n7. My inbox\n8. Access requests\n9. Users (admin)\n10. Export claims CSV\n11. Notification delivery log (admin)\n12. Audit history (admin)\n13. Create fictional demo data (admin)\n14. Request a different role\n0. Sign out");
            try {
                switch (terminal.choice("Choose: ", 14)) {
                    case 0 -> { return; }
                    case 1 -> System.out.println(reports.dashboard(actorId));
                    case 2 -> listClaims();
                    case 3 -> createClaim();
                    case 4 -> manageClaim(terminal.id("Claim ID: "));
                    case 5 -> beneficiary();
                    case 6 -> advances();
                    case 7 -> inbox();
                    case 8 -> accessRequests();
                    case 9 -> users();
                    case 10 -> System.out.println("Saved: " + reports.exportClaims(actorId, terminal.line("Search filter (blank = all): ")));
                    case 11 -> notificationLog();
                    case 12 -> { for (History h : service.auditLog(actorId)) System.out.println(Reports.time(h.at()) + " | " + h.actor() + " | " + h.message()); }
                    case 13 -> demo();
                    case 14 -> requestAccess();
                }
            } catch (DomainException | InvalidPathException e) { error(e); }
        }
    }
    private boolean pendingMenu() {
        System.out.println("Access has not yet been granted. You can review or withdraw your request.\n1. My requests\n2. Submit a new access request\n3. My inbox\n4. Withdraw pending request\n0. Sign out");
        try {
            switch (terminal.choice("Choose: ", 4)) {
                case 0 -> { return false; }
                case 1 -> accessRequests();
                case 2 -> requestAccess();
                case 3 -> inbox();
                case 4 -> { service.withdrawAccess(actorId, terminal.id("Request ID: ")); System.out.println("Request withdrawn."); }
            }
        } catch (DomainException e) { error(e); }
        return true;
    }
    private void requestAccess() {
        Role role = terminal.pick("Requested role", new Role[]{Role.STUDENT, Role.APPROVER}); if (role == null) return;
        service.requestAccess(actorId, role, terminal.line("Reason: ")); System.out.println("Request submitted for administrator review.");
    }
    private void accessRequests() {
        List<AccessRequest> requests = service.requests(actorId);
        Map<String, User> users = new HashMap<>();
        boolean isAdmin = service.user(actorId).role() == Role.ADMIN;
        if (isAdmin) for (User u : service.users(actorId)) users.put(u.id(), u);
        if (requests.isEmpty()) System.out.println("No access requests.");
        for (AccessRequest r : requests) {
            User u = users.get(r.userId());
            System.out.println(r.id() + " | " + (u == null ? r.userId() : u.name() + " <" + u.email() + "> | " + u.branch()) +
                    " | " + r.requestedRole() + " | " + r.status() + "\n  Reason: " + r.reason() + "\n  Decision: " + r.decision());
        }
        if (!isAdmin) return;
        String id = terminal.id("Request ID to decide (blank = back): "); if (id.isEmpty()) return;
        int action = terminal.choice("1 Approve, 2 Reject, 0 Cancel: ", 2); if (action == 0) return;
        AccessRequest r = requests.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> new DomainException("Request not found."));
        boolean all = action == 1 && r.requestedRole() == Role.APPROVER && terminal.yes("Grant reviewer access across all branches");
        String note = terminal.line("Decision reason/note: ");
        if (!terminal.yes("Confirm " + (action == 1 ? "approval" : "rejection") + " of " + id)) return;
        service.decideAccess(actorId, id, action == 1, all, note); System.out.println("Decision saved; local notifications queued.");
    }
    private void users() {
        for (User u : service.users(actorId)) System.out.println(u.id() + " | " + u.name() + " | " + u.email() + " | " + u.role() +
                " | " + u.branch() + (u.allBranches() ? " (all-branch review)" : "") + " | " + (u.suspended() ? "SUSPENDED" : "ACTIVE"));
        String id = terminal.id("User ID to suspend/restore (blank = back): "); if (id.isEmpty()) return;
        int n = terminal.choice("1 Suspend, 2 Restore, 0 Cancel: ", 2); if (n == 0) return;
        if (terminal.yes("Confirm access change")) { service.suspend(actorId, id, n == 1); System.out.println("Access updated."); }
    }
    private void listClaims() {
        List<Claim> claims = service.claims(actorId, terminal.line("Search ID/title/student/status (blank = all): "));
        if (claims.isEmpty()) System.out.println("No matching claims.");
        for (Claim c : claims) System.out.println(c.id() + " | " + c.title() + " | " + c.status() + " | " + Money.format(c.claimedTotal()) +
                " | " + c.settlementLabel() + " | reviewer=" + (c.reviewerId().isEmpty() ? "unassigned" : c.reviewerId()));
    }
    private void createClaim() {
        User user = service.user(actorId); String student = actorId;
        if (user.role() == Role.ADMIN) {
            for (User u : service.users(actorId)) if (u.role() == Role.STUDENT && !u.suspended()) System.out.println(u.id() + " | " + u.name() + " | " + u.prn());
            student = terminal.id("Student ID (blank = your own admin profile): "); if (student.isEmpty()) student = actorId;
        }
        String id = service.createClaim(actorId, student, terminal.line("Claim title: "), terminal.line("Actual purchase/activity purpose: "));
        System.out.println("Draft created: " + id); manageClaim(id);
    }
    private void beneficiary() {
        User actor = service.user(actorId); String studentId = actorId;
        if (actor.role() == Role.ADMIN) { studentId = terminal.id("Student ID (blank = own): "); if (studentId.isEmpty()) studentId = actorId; }
        User target;
        if (actor.role() == Role.ADMIN) {
            String id = studentId;
            target = service.users(actorId).stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow(() -> new DomainException("Student not found."));
        } else target = actor;
        if (target.beneficiary() != null) {
            Beneficiary b = target.beneficiary(); System.out.println("Current: " + b.holder() + " | " + b.bank() + " | " + b.maskedAccount() + " | " + b.ifsc());
        } else System.out.println("No beneficiary saved.");
        if (!terminal.yes("Save a new beneficiary version")) return;
        String holder = terminal.line("Account holder: "), bank = terminal.line("Bank name: ");
        String account = terminal.password("Account digits or fictional DEMO- identifier: ");
        String ifsc = terminal.line("IFSC (demo example DEMO0000001): ");
        service.saveBeneficiary(actorId, studentId, holder, bank, account, ifsc); System.out.println("Encrypted beneficiary saved; normal views show only its masked suffix.");
    }
    private void advances() {
        for (Advance a : service.advances(actorId)) System.out.println(a.id() + " | student=" + a.studentId() + " | " + a.purpose() +
                " | Issued: " + Money.format(a.amount()) + " | Unallocated: " + Money.format(service.availableAdvance(actorId, a.id())) + " | Direct returns: " + Money.format(a.returned()));
        if (service.user(actorId).role() != Role.ADMIN) return;
        int action = terminal.choice("1 Record new advance, 2 Return unallocated amount, 0 Back: ", 2);
        if (action == 1) {
            String sid = terminal.id("Student ID: "), purpose = terminal.line("Purpose: "), amount = terminal.line("Amount INR: ");
            if (terminal.yes("Confirm recording this external/simulated advance")) System.out.println("Advance recorded: " + service.issueAdvance(actorId, sid, purpose, amount));
        } else if (action == 2) {
            String id = terminal.id("Advance ID: "), amount = terminal.line("Return amount INR: "), ref = terminal.line("Unique return reference: ");
            if (terminal.yes("Confirm return record")) { service.returnUnallocatedAdvance(actorId, id, amount, ref); System.out.println("Return recorded."); }
        }
    }
    private void inbox() {
        worker.run(); List<Notification> inbox = service.inbox(actorId);
        if (inbox.isEmpty()) System.out.println("Your inbox is empty.");
        for (Notification n : inbox) System.out.println(n.id() + " | " + (n.read() ? "READ" : "NEW") + " | " + n.subject() + " | " + n.status());
        String id = terminal.id("Notification ID to read (blank = back): "); if (id.isEmpty()) return;
        Notification n = inbox.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> new DomainException("Notification not found."));
        System.out.println("\n" + n.subject() + "\n" + Reports.time(n.created()) + "\n" + n.body() + "\n\nLOCAL notification only; no external email was sent.");
        service.readNotification(actorId, id);
    }
    private void notificationLog() {
        for (Notification n : service.notificationLog(actorId)) System.out.println(n.id() + " | user=" + n.userId() + " | " + n.status() +
                " | attempts=" + n.attempts() + " | " + n.subject() + (n.error().isEmpty() ? "" : " | " + n.error()));
        String id = terminal.id("Failed notification ID to retry (blank = back): ");
        if (!id.isEmpty()) { service.retryNotification(actorId, id); worker.run(); System.out.println("Retry processed. Check delivery status."); }
        System.out.println("Delivery means a local mailbox file, NOT an email sent over the internet.");
    }
    private void demo() {
        System.out.println("Creates two fictional active accounts and an INR 2000 advance. Existing records are not overwritten.");
        if (!terminal.yes("Create demo data")) return;
        service.createDemo(actorId, terminal.newPassword());
        System.out.println("Created student.demo@example.test and approver.demo@example.test with the password you chose.\nThe demo student has a fictional beneficiary and advance. Use samples/stationery-receipt.txt to demonstrate a claim.");
    }
    private void manageClaim(String claimId) {
        while (true) {
            Claim c = service.claim(actorId, claimId); User actor = service.user(actorId);
            System.out.println("\n" + claimId + " | " + c.title() + " | " + c.status() + " | " + Money.format(c.claimedTotal()) + " | " + c.settlementLabel());
            System.out.println("1. View full details/history\n2. Add expense manually\n3. Import text receipt (editable, not AI)\n4. Attach receipt/UPI evidence\n5. Remove expense\n6. Select/remove advance allocation\n7. Submit/resubmit\n8. Withdraw claim\n9. Start review\n10. Approve / partially approve\n11. Request changes\n12. Reject\n13. Assign reviewer (admin)\n14. Record settlement payment/return (admin)\n15. Export text summary\n16. View stored evidence location\n0. Back");
            System.out.println("Only permitted actions for your role and the current claim status will succeed.");
            try {
                switch (terminal.choice("Choose: ", 16)) {
                    case 0 -> { return; }
                    case 1 -> System.out.println(reports.claimText(actorId, claimId));
                    case 2 -> addExpense(claimId, null);
                    case 3 -> addExpense(claimId, Path.of(terminal.path("Text receipt file path: ")));
                    case 4 -> attach(claimId, null, null, "");
                    case 5 -> {
                        expenseList(c); String id = terminal.id("Expense ID to remove: ");
                        if (terminal.yes("Remove this item from the current claim revision")) { service.removeExpense(actorId, claimId, id); System.out.println("Expense removed."); }
                    }
                    case 6 -> linkAdvance(c, actor);
                    case 7 -> {
                        System.out.println(reports.claimText(actorId, claimId));
                        if (terminal.yes("Confirm the expense details and saved beneficiary, then submit")) { service.submit(actorId, claimId); System.out.println("Claim submitted. Local summary and review notifications queued."); }
                    }
                    case 8 -> { if (terminal.yes("Withdraw this claim and release its advance reservation")) { service.withdrawClaim(actorId, claimId); System.out.println("Claim withdrawn."); } }
                    case 9 -> { service.beginReview(actorId, claimId); System.out.println("Review started."); }
                    case 10 -> approve(c);
                    case 11 -> { service.reviewDecision(actorId, claimId, false, terminal.line("Changes required: ")); System.out.println("Changes requested."); }
                    case 12 -> { String reason = terminal.line("Rejection reason: "); if (terminal.yes("Reject this claim")) { service.reviewDecision(actorId, claimId, true, reason); System.out.println("Claim rejected; advance reservation released."); } }
                    case 13 -> {
                        for (User u : service.users(actorId)) if (u.role() == Role.APPROVER || u.role() == Role.ADMIN) System.out.println(u.id() + " | " + u.name() + " | " + u.branch() + " | " + (u.allBranches() ? "all branches" : "branch scope"));
                        service.assign(actorId, claimId, terminal.id("Reviewer ID: ")); System.out.println("Reviewer assigned.");
                    }
                    case 14 -> {
                        System.out.println("Current settlement: " + c.settlementLabel() + ". No bank transfer will be initiated.");
                        String amount = terminal.line("Amount INR: "), reference = terminal.line("Unique payment/return reference: ");
                        if (terminal.yes("Confirm this external/simulated payment record")) { service.recordPayment(actorId, claimId, amount, reference); System.out.println("Payment record saved."); }
                    }
                    case 15 -> System.out.println("Saved: " + reports.exportClaim(actorId, claimId));
                    case 16 -> evidence(c);
                }
            } catch (DomainException | InvalidPathException e) { error(e); }
        }
    }
    private void expenseList(Claim c) {
        for (ExpenseItem i : c.items()) System.out.println(i.id() + " | " + i.vendor() + " | " + Money.format(i.claimed()) + " | attachments=" + i.evidence().size());
    }
    private void addExpense(String claimId, Path source) {
        Map<String, String> data = source == null ? Map.of() : parser.extract(source);
        if (source != null) {
            System.out.println("Extracted text fields: " + data + "\nKeyword suggestion only - verify every field. No AI/OCR is used.");
            System.out.println("Suggested category: " + parser.suggest(data));
        }
        String vendor = terminal.withDefault("Vendor/payee", data.getOrDefault("vendor", ""));
        String purpose = terminal.withDefault("Purchase purpose", data.getOrDefault("purpose", data.getOrDefault("items", "")));
        Category category = terminal.pick("Expense category", Category.values()); if (category == null) return;
        String amount = terminal.withDefault("Amount INR", data.getOrDefault("amount", ""));
        LocalDate date = terminal.date(terminal.withDefault("Date YYYY-MM-DD", data.getOrDefault("date", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())));
        String id = service.addExpense(actorId, claimId, vendor, purpose, category, date, amount);
        System.out.println("Expense added: " + id);
        if (source != null && terminal.yes("Attach this text receipt as evidence")) attach(claimId, id, source, data.getOrDefault("reference", ""));
    }
    private void attach(String claimId, String itemId, Path source, String suggestedReference) {
        Claim c = service.claim(actorId, claimId);
        if (itemId == null) { expenseList(c); itemId = terminal.id("Expense ID: "); }
        int type = terminal.choice("1 Receipt, 2 UPI screenshot, 0 Cancel: ", 2); if (type == 0) return;
        if (source == null) source = Path.of(terminal.path("File path (TXT/PDF/PNG/JPG/WebP): "));
        String reference = terminal.withDefault("Invoice/transaction reference (blank if unknown)", suggestedReference);
        String status = "UNKNOWN";
        if (type == 2) { status = terminal.pick("Displayed UPI status (not bank verification)", new String[]{"SUCCESS", "PENDING", "FAILED", "UNKNOWN"}); if (status == null) return; }
        ExpenseEvidence evidence = documents.importFile(source, type == 2, reference, status);
        try { service.attach(actorId, claimId, itemId, evidence); }
        catch (RuntimeException e) { documents.discardNewCopy(evidence); throw e; }
        System.out.println("Evidence copied and linked; expense amount is unchanged. " + evidence.id());
    }
    private void linkAdvance(Claim c, User actor) {
        for (Advance a : service.advances(actorId)) if (a.studentId().equals(c.studentId())) System.out.println(a.id() + " | " + a.purpose() + " | available=" + Money.format(service.availableAdvance(actorId, a.id())));
        System.out.println("Current selection: " + c.advanceId() + " " + Money.format(c.advanceApplied()) + ". Existing reserved allocation can be retained when correcting a claim.");
        String id = terminal.id("Advance ID (blank = remove selection): ");
        String amount = id.isEmpty() ? "0" : terminal.line("Amount to apply INR: ");
        service.linkAdvance(actorId, c.id(), id, amount); System.out.println("Advance selection saved. Draft selection is reserved only on submission.");
    }
    private void approve(Claim c) {
        System.out.println(reports.claimText(actorId, c.id()));
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (ExpenseItem item : c.items()) amounts.put(item.id(), Money.parse(terminal.withDefault("Approve " + item.id() + " " + item.vendor(), item.claimed().toPlainString())));
        String note = terminal.line("Approval note (required if reduced): ");
        List<String> warnings = service.warnings(actorId, c.id());
        String resolution = warnings.isEmpty() ? "" : terminal.line("Explain how all evidence warnings were verified/resolved: ");
        if (terminal.yes("Confirm approval amounts")) { service.approve(actorId, c.id(), amounts, note, resolution); System.out.println("Approval saved; reimbursement has NOT automatically been paid."); }
    }
    private void evidence(Claim c) {
        for (ExpenseItem item : c.items()) for (ExpenseEvidence e : item.evidence()) {
            Path path = documents.path(e); System.out.println(item.id() + " | " + e.kind() + " | " + path);
            if (e.storedName().endsWith(".txt") && terminal.yes("Show text preview (up to 4000 characters)")) {
                try (java.io.Reader reader = Files.newBufferedReader(path)) {
                    char[] preview = new char[4000]; int count = reader.read(preview);
                    if (count > 0) System.out.println(new String(preview, 0, count).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?"));
                } catch (java.io.IOException ex) { throw new DomainException("Cannot preview attachment: " + ex.getMessage()); }
            }
        }
    }
}
