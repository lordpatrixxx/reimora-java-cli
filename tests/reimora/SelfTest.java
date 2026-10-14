package reimora;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import reimora.Models.*;
import static reimora.Types.*;

/** Dependency-free integration tests exercising actual files and domain rules. */
public final class SelfTest {
    private static int checks;
    private static final String PASSWORD = "Test-only-password-42";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS " + checks + " - " + message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Expected rejection: " + message); }
        catch (DomainException expected) { check(true, message); }
    }
    private static BigDecimal m(String amount) { return Money.parse(amount); }
    private static void approveAccess(ReimoraService s, String admin, String user, boolean allBranches) {
        AccessRequest request = s.requests(admin).stream().filter(r -> r.userId().equals(user) && r.status() == RequestStatus.PENDING).findFirst().orElseThrow();
        s.decideAccess(admin, request.id(), true, allBranches, "Test identity and scope checked.");
    }
    private static String register(ReimoraService s, String name, String email, String id, Branch branch, Role role) {
        return s.register(name, email, id, "B", branch, PASSWORD, role, "Automated test account.");
    }
    private static String expense(ReimoraService s, DocumentStorage docs, Path root, String actor, String claim, String amount, String ref, String vendor) throws IOException {
        String id = s.addExpense(actor, claim, vendor, "Test project materials", Category.STATIONERY, LocalDate.of(2026, 10, 14), amount);
        Path receipt = root.resolve(ref + ".txt"); Files.writeString(receipt, "Vendor: " + vendor + "\nAmount: " + amount + "\nReference: " + ref);
        s.attach(actor, claim, id, docs.importFile(receipt, false, ref, "UNKNOWN"));
        return id;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("reimora-selftest-").toAbsolutePath().normalize();
        String adminId, studentId, approverId, settledClaim;
        Path data = root.resolve("data");
        try {
            try (FileRepository repo = new FileRepository(data)) {
                Security security = new Security(data);
                ReimoraService s = new ReimoraService(repo, security, CLOCK);
                DocumentStorage docs = new DocumentStorage(data.resolve("receipts"));
                adminId = s.setupAdmin(PASSWORD); final String admin = adminId;
                check(s.login(ReimoraService.ADMIN_EMAIL.toUpperCase(Locale.ROOT), PASSWORD).equals(admin), "Administrator login and normalized email");
                rejects(() -> s.setupAdmin(PASSWORD), "Cannot bootstrap a second administrator");
                rejects(() -> s.login(ReimoraService.ADMIN_EMAIL, "wrong"), "Incorrect password rejected");
                check(!Security.hashPassword(PASSWORD).equals(Security.hashPassword(PASSWORD)), "Password hashes use different random salts");
                try { new FileRepository(data).close(); throw new AssertionError("Second writer was permitted"); }
                catch (IOException expected) { check(true, "Concurrent application instance blocked by file lock"); }

                studentId = register(s, "Test Student", "student@example.test", "TEST-STUDENT", Branch.IT, Role.STUDENT); final String student = studentId;
                check(s.user(student).role() == Role.PENDING, "Registration grants no automatic student role");
                check(s.inbox(student).size() == 2 && s.inbox(admin).size() == 2, "Registration acknowledgement and admin access-request event created");
                rejects(() -> s.createClaim(student, "Blocked", "Pending user"), "Pending user cannot file claims");
                rejects(() -> s.users(student), "Student cannot view admin user directory");
                rejects(() -> s.requestAccess(student, Role.APPROVER, "Duplicate"), "Duplicate pending access request rejected");
                approveAccess(s, admin, student, false);
                check(s.user(student).role() == Role.STUDENT, "Administrator approval grants student access");
                rejects(() -> register(s, "Duplicate", "student@example.test", "OTHER-ID", Branch.IT, Role.STUDENT), "Duplicate account registration rejected");
                rejects(() -> register(s, "Duplicate", "another@example.test", "TEST-STUDENT", Branch.IT, Role.STUDENT), "Duplicate PRN rejected");

                approverId = register(s, "Test Approver", "approver@example.test", "STAFF-IT", Branch.IT, Role.APPROVER); final String approver = approverId;
                approveAccess(s, admin, approver, false);
                String otherApprover = register(s, "Mechanical Approver", "mechanical@example.test", "STAFF-MECH", Branch.MECHANICAL, Role.APPROVER);
                approveAccess(s, admin, otherApprover, false);
                String otherStudent = register(s, "Other Student", "other@example.test", "OTHER-STUDENT", Branch.IT, Role.STUDENT);
                approveAccess(s, admin, otherStudent, false);
                s.saveBeneficiary(student, student, "Test Student", "Demo Bank", "DEMO-12345678", "DEMO0000001");
                Beneficiary original = s.user(student).beneficiary();
                check(original.maskedAccount().equals("****5678") && !original.encryptedAccount().contains("12345678"), "Bank account encrypted and display masked");
                rejects(() -> s.saveBeneficiary(otherStudent, student, "Wrong", "Bank", "DEMO-77777777", "DEMO0000001"), "Cannot modify another student's beneficiary");

                String claim = s.createClaim(student, "Project supplies", "Mini project materials"); settledClaim = claim;
                rejects(() -> s.submit(student, claim), "Empty claim cannot be submitted");
                rejects(() -> s.addExpense(student, claim, "Vendor", "Purpose", Category.OTHER, LocalDate.of(2026,10,15), "10"), "Future expense date rejected");
                String item = expense(s, docs, root, student, claim, "2650", "TEST-INV-1", "Stationery One");
                Path upiFile = root.resolve("payment.txt"); Files.writeString(upiFile, "Synthetic UPI SUCCESS: TEST-UPI-1");
                s.attach(student, claim, item, docs.importFile(upiFile, true, "TEST-UPI-1", "SUCCESS"));
                check(s.claim(student, claim).claimedTotal().compareTo(m("2650")) == 0, "Receipt plus UPI evidence does not double count expense");
                rejects(() -> s.claim(otherStudent, claim), "Other student cannot read private claim");
                String advance = s.issueAdvance(admin, student, "Materials", "2000");
                s.linkAdvance(student, claim, advance, "2000");
                check(s.availableAdvance(student, advance).compareTo(m("2000")) == 0, "Draft advance selection does not reserve money");
                int beforeSubmit = s.inbox(student).size();
                s.submit(student, claim);
                check(s.claim(student, claim).reviewerId().equals(approver), "Claim routed to matching branch approver");
                check(s.availableAdvance(student, advance).signum() == 0, "Submitted claim reserves advance");
                check(s.inbox(student).size() == beforeSubmit + 1, "Submission produces student claim summary");
                int afterSubmit = s.inbox(student).size();
                rejects(() -> s.submit(student, claim), "Repeated submission rejected");
                check(s.inbox(student).size() == afterSubmit, "Repeated submission does not duplicate notifications");
                rejects(() -> s.removeExpense(student, claim, item), "Submitted expense cannot be silently edited");
                rejects(() -> s.approve(otherApprover, claim, Map.of(item, m("2650")), "", ""), "Out-of-scope approver blocked");
                rejects(() -> s.assign(admin, claim, otherApprover), "Admin cannot assign an out-of-scope reviewer");
                rejects(() -> s.approve(approver, claim, Map.of(item, m("2651")), "", ""), "Approval cannot exceed amount claimed");
                check(s.claim(student, claim).items().get(0).approved().signum() == 0, "Invalid approval rolls back tentative changes");

                String competing = s.createClaim(student, "Second claim", "Other purpose");
                rejects(() -> s.linkAdvance(student, competing, advance, "1"), "Reserved advance cannot be applied to another claim");
                rejects(() -> s.returnUnallocatedAdvance(admin, advance, "1", "INVALID-RETURN"), "Reserved advance cannot be returned as unallocated cash");
                s.beginReview(approver, claim);
                s.approve(approver, claim, Map.of(item, m("2650")), "Evidence checked", "");
                check(s.claim(student, claim).remaining().compareTo(m("650")) == 0, "2650 approved minus 2000 advance leaves 650 payable");
                s.saveBeneficiary(student, student, "Test Student", "New Demo Bank", "DEMO-99990000", "DEMO0000001");
                check(s.claim(student, claim).beneficiary().id().equals(original.id()), "Beneficiary edits preserve submitted claim snapshot");
                rejects(() -> s.recordPayment(student, claim, "10", "UNAUTHORIZED"), "Student cannot record finance payments");
                rejects(() -> s.recordPayment(admin, claim, "651", "OVERPAY"), "Overpayment rejected");
                s.recordPayment(admin, claim, "300", "TEST-PAY-1");
                check(s.claim(student, claim).remaining().compareTo(m("350")) == 0, "Partial payment reduces outstanding balance");
                rejects(() -> s.recordPayment(admin, claim, "100", "TEST-PAY-1"), "Repeated payment reference rejected");
                AtomicInteger success = new AtomicInteger(); ExecutorService pool = Executors.newFixedThreadPool(2);
                List<Callable<Void>> calls = List.of(
                        () -> { try { s.recordPayment(admin, claim, "350", "RACE-A"); success.incrementAndGet(); } catch (DomainException ignored) { } return null; },
                        () -> { try { s.recordPayment(admin, claim, "350", "RACE-B"); success.incrementAndGet(); } catch (DomainException ignored) { } return null; });
                for (Future<Void> result : pool.invokeAll(calls)) result.get(); pool.shutdown();
                check(success.get() == 1 && s.claim(student, claim).remaining().signum() == 0, "Concurrent settlement requests cannot overpay");
                check(s.claim(student, claim).settlementLabel().equals("SETTLED"), "Fully reimbursed claim becomes settled");
                check(s.claim(student, claim).payments().get(0).beneficiary().id().equals(original.id()), "Payment preserves beneficiary snapshot");

                String refundClaim = s.createClaim(student, "Refreshments", "Volunteer snacks");
                String refundItem = expense(s, docs, root, student, refundClaim, "1900", "TEST-INV-2", "Cafe Two");
                String advance2 = s.issueAdvance(admin, student, "Refreshments", "2000");
                s.linkAdvance(student, refundClaim, advance2, "2000"); s.submit(student, refundClaim);
                rejects(() -> s.approve(approver, refundClaim, Map.of(refundItem, m("1700")), "", ""), "Partial approval requires a reason");
                s.approve(approver, refundClaim, Map.of(refundItem, m("1700")), "200 excluded by reviewer", "");
                check(s.claim(student, refundClaim).netSettlement().compareTo(new BigDecimal("-300.00")) == 0, "1700 approved minus 2000 advance leaves 300 returnable");
                s.recordPayment(admin, refundClaim, "100", "RETURN-A"); s.recordPayment(admin, refundClaim, "200", "RETURN-B");
                check(s.claim(student, refundClaim).settlementLabel().equals("SETTLED") && s.claim(student, refundClaim).payments().get(0).kind() == PaymentKind.ADVANCE_RETURN, "Partial advance returns settle in the correct direction");

                String changes = s.createClaim(student, "Corrections", "Review flow");
                String changeItem = expense(s, docs, root, student, changes, "500", "TEST-INV-3", "Vendor Three");
                String advance3 = s.issueAdvance(admin, student, "Corrections", "400");
                s.linkAdvance(student, changes, advance3, "400"); s.submit(student, changes);
                s.reviewDecision(approver, changes, false, "Please clarify the purpose.");
                check(s.availableAdvance(student, advance3).signum() == 0, "Changes-requested claim retains its advance reservation");
                s.removeExpense(student, changes, changeItem);
                String revisedItem = expense(s, docs, root, student, changes, "450", "TEST-INV-4", "Vendor Four");
                s.submit(student, changes); check(s.claim(student, changes).revision() == 2, "Corrected claim resubmits with a new revision");
                check(s.claim(student, changes).revisions().get(0).expenses().get(0).amount().compareTo(m("500")) == 0 &&
                        s.claim(student, changes).revisions().get(1).expenses().get(0).amount().compareTo(m("450")) == 0,
                        "Earlier submitted expense details survive removal and corrected resubmission");
                s.reviewDecision(approver, changes, true, "Not eligible in this demonstration.");
                check(s.availableAdvance(student, advance3).compareTo(m("400")) == 0, "Rejection releases reserved advance");
                s.returnUnallocatedAdvance(admin, advance3, "150", "DIRECT-RETURN");
                check(s.availableAdvance(student, advance3).compareTo(m("250")) == 0, "Direct return reduces unallocated advance balance");

                String duplicate = s.createClaim(student, "Duplicate example", "Evidence check");
                String duplicateItem = s.addExpense(student, duplicate, "Different vendor", "Test", Category.OTHER, LocalDate.of(2026,10,14), "25");
                s.attach(student, duplicate, duplicateItem, docs.importFile(root.resolve("TEST-INV-1.txt"), false, "TEST-INV-1", "UNKNOWN"));
                s.submit(student, duplicate);
                check(!s.warnings(approver, duplicate).isEmpty(), "Identical evidence on another expense produces warnings");
                rejects(() -> s.approve(approver, duplicate, Map.of(duplicateItem, m("25")), "", ""), "Warnings require documented resolution before approval");
                s.approve(approver, duplicate, Map.of(duplicateItem, m("25")), "Test approval", "Synthetic duplicate intentionally accepted in test.");

                s.saveBeneficiary(admin, admin, "Admin Demo", "Bank", "DEMO-12340000", "DEMO0000001");
                String own = s.createClaim(admin, "Admin own claim", "Self-approval guard");
                String ownItem = expense(s, docs, root, admin, own, "10", "TEST-INV-5", "Vendor Five"); s.submit(admin, own);
                rejects(() -> s.approve(admin, own, Map.of(ownItem, m("10")), "", ""), "Administrator cannot approve own reimbursement");
                s.withdrawClaim(admin, own); check(s.claim(admin, own).status() == ClaimStatus.WITHDRAWN, "Claim can be withdrawn before approval");

                int welcomeCount = s.inbox(student).size(); s.login("student@example.test", PASSWORD); s.login("student@example.test", PASSWORD);
                check(s.inbox(student).size() == welcomeCount, "Logging in again does not resend welcome messages");
                s.suspend(admin, otherStudent, true); rejects(() -> s.claims(otherStudent), "Suspension blocks an already-known session ID");
                rejects(() -> s.suspend(admin, admin, true), "Main administrator cannot be suspended");
                s.suspend(admin, otherStudent, false); check(s.user(otherStudent).role() == Role.STUDENT, "Account restoration preserves granted role");

                Reports reports = new Reports(s, root.resolve("reports"));
                Path csv = reports.exportClaims(student, "Project supplies");
                String csvText = Files.readString(csv);
                check(csvText.contains("2650.00") && !csvText.contains("DEMO-12345678"), "CSV report contains financial totals without full bank identifiers");
                check(Reports.csv("=HYPERLINK(\"x\")").startsWith("\"'="), "CSV export neutralizes spreadsheet formula injection");
                Path text = reports.exportClaim(student, claim); check(Files.readString(text).contains("SETTLED"), "Printable claim summary includes actual settlement state");
                check(reports.dashboard(admin).contains("Unallocated advances"), "Dashboard includes unallocated advance balance");

                Path textReceipt = root.resolve("parse.txt");
                Files.writeString(textReceipt, "Vendor: Demo Shop\nAmount: 123.45\nDate: 2026-10-14\nItems: notebooks and pens\n");
                TextReceiptParser parser = new TextReceiptParser(); Map<String,String> fields = parser.extract(textReceipt);
                check(fields.get("amount").equals("123.45") && parser.suggest(fields) == Category.STATIONERY, "Text receipt parser extracts fields and suggests a category");
                check(parser.suggest(Map.of("vendor", "A Person")) == Category.OTHER, "Vendor name alone does not invent a purchase purpose");
                Path fakePng = root.resolve("fake.png"); Files.writeString(fakePng, "not a PNG");
                rejects(() -> docs.importFile(fakePng, false, "", "UNKNOWN"), "Mismatched attachment file type rejected");
                rejects(() -> Money.parse("0.001"), "Sub-paise values rejected instead of silently rounded");
                check(Files.readString(root.resolve("TEST-INV-1.txt")).contains("Stationery One"), "Import preserves the original receipt file");

                NotificationWorker failing = new NotificationWorker(repo, (n, u) -> { throw new IOException("Synthetic delivery failure"); });
                failing.run();
                check(s.notificationLog(admin).stream().anyMatch(n -> n.status() == MailStatus.FAILED), "Delivery failures remain visible in notification log");
                check(s.claim(student, claim).settlementLabel().equals("SETTLED"), "Notification failure does not roll back a completed business operation");
                NotificationWorker worker = new NotificationWorker(repo, new LocalMailboxDelivery(data.resolve("mailbox")));
                worker.run();
                check(s.notificationLog(admin).stream().allMatch(n -> n.status() == MailStatus.LOCAL_SAVED), "Failed notifications retry into local mailbox files");
                long count;
                try (var stream = Files.walk(data.resolve("mailbox"))) { count = stream.filter(p -> p.toString().endsWith(".txt")).count(); }
                worker.run();
                try (var stream = Files.walk(data.resolve("mailbox"))) { check(stream.filter(p -> p.toString().endsWith(".txt")).count() == count, "Repeated worker runs do not create duplicate messages"); }
                check(Files.exists(data.resolve("state.bin.bak")), "Previous-state backup created");
                check(!new String(Files.readAllBytes(data.resolve("state.bin")), java.nio.charset.StandardCharsets.ISO_8859_1).contains(PASSWORD), "State does not contain plaintext passwords");
            }
            try (FileRepository reopened = new FileRepository(data)) {
                ReimoraService s = new ReimoraService(reopened, new Security(data), CLOCK);
                check(s.login(ReimoraService.ADMIN_EMAIL, PASSWORD).equals(adminId), "Accounts persist across application restart");
                check(s.claim(studentId, settledClaim).settlementLabel().equals("SETTLED"), "Claims and payments persist across restart");
                check(s.user(studentId).beneficiary().maskedAccount().equals("****0000"), "Updated beneficiary persists across restart");
            }
            Path corrupt = root.resolve("corrupt"); Files.createDirectories(corrupt); Files.writeString(corrupt.resolve("state.bin"), "not a valid state");
            try { new FileRepository(corrupt).close(); throw new AssertionError("Corrupt state was silently replaced"); }
            catch (IOException expected) { check(Files.readString(corrupt.resolve("state.bin")).equals("not a valid state"), "Corrupt state is preserved and startup fails clearly"); }
            System.out.println("\nALL " + checks + " CHECKS PASSED. All fixtures were fictional and no external email was sent.");
        } finally {
            // Only delete this exact temporary test workspace; never the user's application data.
            if (root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) && root.getFileName().toString().startsWith("reimora-selftest-")) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}
