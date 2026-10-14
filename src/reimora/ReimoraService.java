package reimora;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import reimora.Models.*;
import static reimora.Types.*;

/** Application use cases; all writes pass through repository transactions. */
public final class ReimoraService {
    public static final String ADMIN_EMAIL = "pratik.phapale25@pccoepune.org";
    private final Repository repository;
    private final Security security;
    private final Clock clock;
    ReimoraService(Repository repository, Security security, Clock clock) {
        this.repository = repository; this.security = security; this.clock = clock;
    }
    private Instant now() { return clock.instant(); }
    static String required(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new DomainException(label + " is required (up to " + maximum + " characters, no control characters).");
        return value.trim();
    }
    private static User active(State s, String id) {
        User u = s.users.get(id);
        if (u == null || u.suspended()) throw new DomainException("Account is unavailable or suspended. Sign out and contact the administrator.");
        return u;
    }
    private static User admin(State s, String id) {
        User u = active(s, id);
        if (u.role() != Role.ADMIN) throw new DomainException("Administrator access is required.");
        return u;
    }
    private static Claim find(State s, String id) {
        Claim c = s.claims.get(id);
        if (c == null) throw new DomainException("Claim not found.");
        return c;
    }
    private static boolean inScope(User reviewer, User student) {
        return reviewer.role() == Role.ADMIN || (reviewer.role() == Role.APPROVER &&
                (reviewer.allBranches() || reviewer.branch() == student.branch()));
    }
    private static boolean canRead(State s, User actor, Claim c) {
        return actor.role() == Role.ADMIN || actor.id().equals(c.studentId()) ||
                (actor.role() == Role.APPROVER && actor.id().equals(c.reviewerId()) && inScope(actor, s.users.get(c.studentId())));
    }
    private static Claim editable(State s, String actorId, String claimId) {
        User actor = active(s, actorId); Claim c = find(s, claimId);
        if (!(actor.role() == Role.ADMIN || actor.id().equals(c.studentId()))) throw new DomainException("You cannot edit this claim.");
        if (!c.editable()) throw new DomainException("Only drafts and claims requiring changes can be edited.");
        return c;
    }
    private static Claim reviewable(State s, String actorId, String claimId) {
        User actor = active(s, actorId); Claim c = find(s, claimId);
        if (actor.id().equals(c.studentId())) throw new DomainException("You cannot approve or review your own claim.");
        if (!inScope(actor, s.users.get(c.studentId())) ||
                (actor.role() != Role.ADMIN && !actor.id().equals(c.reviewerId())))
            throw new DomainException("This claim is outside your assigned reviewer access.");
        if (c.status() != ClaimStatus.SUBMITTED && c.status() != ClaimStatus.UNDER_REVIEW)
            throw new DomainException("This claim is not awaiting review.");
        return c;
    }
    private void audit(State s, String actor, String message) { s.audit.add(new History(now(), actor, message)); }
    private void notify(State s, String userId, String subject, String body) {
        String id = s.next("N"); s.notifications.put(id, new Notification(id, userId, subject, body, now()));
    }
    private void notifyAdmins(State s, String subject, String body) {
        for (User u : s.users.values()) if (u.role() == Role.ADMIN && !u.suspended()) notify(s, u.id(), subject, body);
    }
    public boolean needsSetup() { return repository.snapshot().users.isEmpty(); }
    public String setupAdmin(String password) {
        String hash = Security.hashPassword(password);
        return repository.update(s -> {
            if (!s.users.isEmpty()) throw new DomainException("Administrator setup has already been completed.");
            String id = s.next("U");
            s.users.put(id, new User(id, "Pratik Deepak Phapale", ADMIN_EMAIL, "125B1F210", "B", Branch.IT, Role.ADMIN, hash));
            audit(s, id, "Administrator initialized.");
            notify(s, id, "Welcome to Reimora", "Administrator setup is complete. Notifications are saved locally, not emailed.");
            return id;
        });
    }
    public String login(String email, String password) {
        State s = repository.snapshot();
        User u = s.users.values().stream().filter(x -> x.email().equalsIgnoreCase(email.trim())).findFirst().orElse(null);
        if (u == null || !u.verifyPassword(password)) throw new DomainException("Incorrect email or password.");
        active(s, u.id()); return u.id();
    }
    public String register(String name, String email, String prn, String division, Branch branch, String password, Role requested, String reason) {
        String n = required(name, "Name", 100), em = required(email, "Email", 150).toLowerCase(Locale.ROOT);
        if (!em.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new DomainException("Enter a valid email address.");
        String p = required(prn, "PRN or faculty ID", 40).toUpperCase(Locale.ROOT);
        String d = required(division, "Division/designation", 60), why = required(reason, "Reason", 500);
        if (branch == null || (requested != Role.STUDENT && requested != Role.APPROVER)) throw new DomainException("Choose a branch and Student/Approver role.");
        String hash = Security.hashPassword(password);
        return repository.update(s -> {
            if (s.users.isEmpty()) throw new DomainException("Administrator setup must be completed first.");
            if (s.users.values().stream().anyMatch(u -> u.email().equals(em) || u.prn().equalsIgnoreCase(p)))
                throw new DomainException("An account with this email or PRN/ID already exists.");
            String id = s.next("U");
            s.users.put(id, new User(id, n, em, p, d, branch, Role.PENDING, hash));
            notify(s, id, "Registration successful", "Welcome, " + n + ". Your account is registered; requested access is pending administrator review.");
            createRequest(s, id, requested, why); audit(s, id, "Registered account and requested " + requested + " access.");
            return id;
        });
    }
    private String createRequest(State s, String userId, Role role, String reason) {
        if (role != Role.STUDENT && role != Role.APPROVER) throw new DomainException("Only Student/Approver access can be requested.");
        if (s.requests.values().stream().anyMatch(r -> r.userId().equals(userId) && r.status() == RequestStatus.PENDING))
            throw new DomainException("You already have a pending access request.");
        User u = s.users.get(userId);
        String id = s.next("R"); s.requests.put(id, new AccessRequest(id, userId, role, reason, now()));
        notifyAdmins(s, "New access request " + id, u.name() + " <" + u.email() + "> requested " + role +
                "\nBranch: " + u.branch() + "\nReason: " + reason + "\nReview in Access Requests.");
        notify(s, userId, "Access request received " + id, "Requested role: " + role + ". Status: PENDING.");
        return id;
    }
    public void requestAccess(String actorId, Role role, String reason) {
        String why = required(reason, "Reason", 500);
        repository.update(s -> { User u = active(s, actorId);
            if (u.role() == Role.ADMIN || u.role() == role) throw new DomainException("You already hold this access or administrator access.");
            createRequest(s, actorId, role, why); audit(s, actorId, "Requested " + role + " access."); return null;
        });
    }
    public User user(String id) { return active(repository.snapshot(), id); }
    public List<User> users(String actorId) { State s = repository.snapshot(); admin(s, actorId); return List.copyOf(s.users.values()); }
    public List<AccessRequest> requests(String actorId) {
        State s = repository.snapshot(); User u = active(s, actorId);
        return s.requests.values().stream().filter(r -> u.role() == Role.ADMIN || r.userId().equals(actorId)).toList();
    }
    public void decideAccess(String actorId, String requestId, boolean approve, boolean allBranches, String reason) {
        String why = required(reason, "Decision note", 500);
        repository.update(s -> {
            admin(s, actorId); AccessRequest r = s.requests.get(requestId);
            if (r == null || r.status() != RequestStatus.PENDING) throw new DomainException("Pending request not found.");
            User target = s.users.get(r.userId());
            if (target.role() == Role.ADMIN) throw new DomainException("Administrator access cannot be changed here.");
            r.decide(approve ? RequestStatus.APPROVED : RequestStatus.REJECTED, why);
            if (approve) target.grant(r.requestedRole(), r.requestedRole() == Role.APPROVER && allBranches);
            notify(s, target.id(), "Access " + r.status() + " - " + r.id(), "Requested role: " + r.requestedRole() + "\n" + why);
            audit(s, actorId, "Access " + r.id() + " " + r.status() + "; all-branches=" + allBranches + ": " + why);
            return null;
        });
    }
    public void withdrawAccess(String actorId, String requestId) {
        repository.update(s -> { active(s, actorId); AccessRequest r = s.requests.get(requestId);
            if (r == null || !r.userId().equals(actorId) || r.status() != RequestStatus.PENDING) throw new DomainException("Your pending request was not found.");
            r.decide(RequestStatus.WITHDRAWN, "Withdrawn by applicant."); audit(s, actorId, "Withdrew " + requestId); return null;
        });
    }
    public void suspend(String actorId, String userId, boolean suspend) {
        repository.update(s -> { admin(s, actorId); User u = s.users.get(userId);
            if (u == null) throw new DomainException("User not found.");
            if (u.role() == Role.ADMIN) throw new DomainException("The main administrator cannot be suspended.");
            u.suspend(suspend); audit(s, actorId, (suspend ? "Suspended " : "Restored ") + userId);
            notify(s, userId, "Account access updated", suspend ? "Your account is suspended. Contact the administrator." : "Your account access has been restored."); return null;
        });
    }
    public void saveBeneficiary(String actorId, String studentId, String holder, String bank, String account, String ifsc) {
        String h = required(holder, "Account holder", 100), b = required(bank, "Bank", 100);
        String acc = required(account, "Account identifier", 30), code = required(ifsc, "IFSC", 11).toUpperCase(Locale.ROOT);
        if (!acc.matches("[0-9]{6,20}|DEMO-[A-Za-z0-9-]{4,24}")) throw new DomainException("Use 6-20 account digits or a DEMO- identifier.");
        if (!code.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw new DomainException("IFSC must have four letters, zero, and six letters/digits.");
        String encrypted = security.encrypt(acc), masked = "****" + acc.substring(acc.length() - 4);
        repository.update(s -> { User actor = active(s, actorId), student = active(s, studentId);
            if (actor.role() != Role.ADMIN && !actorId.equals(studentId)) throw new DomainException("You cannot change this beneficiary.");
            if (student.role() != Role.STUDENT && student.role() != Role.ADMIN) throw new DomainException("Student access is required for a beneficiary.");
            student.beneficiary(new Beneficiary(s.next("B"), h, b, encrypted, masked, code));
            audit(s, actorId, "Updated beneficiary for " + studentId + " (" + masked + ").");
            notify(s, studentId, "Beneficiary updated", "Bank: " + b + "\nAccount: " + masked + "\nExisting submitted/payment snapshots are unchanged."); return null;
        });
    }
    public String createClaim(String actorId, String title, String purpose) { return createClaim(actorId, actorId, title, purpose); }
    public String createClaim(String actorId, String studentId, String title, String purpose) {
        String t = required(title, "Title", 120), p = required(purpose, "Purpose", 500);
        return repository.update(s -> { User actor = active(s, actorId), student = active(s, studentId);
            if (actor.role() != Role.ADMIN && (actor.role() != Role.STUDENT || !actorId.equals(studentId))) throw new DomainException("Student access is required.");
            if (student.role() != Role.STUDENT && student.role() != Role.ADMIN) throw new DomainException("Select an active student.");
            String id = s.next("C"); Claim c = new Claim(id, studentId, t, p, now());
            c.history(now(), actorId, "Draft created."); s.claims.put(id, c); audit(s, actorId, "Created " + id); return id;
        });
    }
    public String addExpense(String actorId, String claimId, String vendor, String purpose, Category category, LocalDate date, String amount) {
        String v = required(vendor, "Vendor", 100), p = required(purpose, "Expense purpose", 300);
        BigDecimal value = Money.positive(amount);
        if (category == null || date == null || date.isAfter(LocalDate.now(clock))) throw new DomainException("Choose a category and a valid date that is not in the future.");
        return repository.update(s -> { Claim c = editable(s, actorId, claimId); String id = s.next("E");
            c.add(new ExpenseItem(id, v, p, category, date, value)); c.history(now(), actorId, "Added expense " + id + ": " + Money.format(value)); return id;
        });
    }
    public void removeExpense(String actorId, String claimId, String itemId) {
        repository.update(s -> { Claim c = editable(s, actorId, claimId); c.remove(itemId);
            c.history(now(), actorId, "Removed expense " + itemId + " from this revision; original attachment files retained."); return null;
        });
    }
    public void attach(String actorId, String claimId, String itemId, ExpenseEvidence evidence) {
        repository.update(s -> { Claim c = editable(s, actorId, claimId);
            ExpenseItem item = c.items().stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow(() -> new DomainException("Expense not found."));
            if (item.evidence().stream().anyMatch(e -> e.sha256().equals(evidence.sha256()))) throw new DomainException("This file is already attached to the expense.");
            item.attach(evidence); c.history(now(), actorId, "Attached " + evidence.kind() + " to " + itemId); return null;
        });
    }
    public Claim claim(String actorId, String claimId) {
        State s = repository.snapshot(); User u = active(s, actorId); Claim c = find(s, claimId);
        if (!canRead(s, u, c)) throw new DomainException("You cannot access this claim."); return c;
    }
    public List<Claim> claims(String actorId) { return claims(actorId, ""); }
    public List<Claim> claims(String actorId, String search) {
        State s = repository.snapshot(); User u = active(s, actorId); String q = search.toLowerCase(Locale.ROOT);
        return s.claims.values().stream().filter(c -> canRead(s, u, c)).filter(c ->
                (c.id() + " " + c.title() + " " + c.status() + " " + s.users.get(c.studentId()).name()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    public String studentLabel(String actorId, String claimId) {
        Claim c = claim(actorId, claimId); User u = repository.snapshot().users.get(c.studentId());
        return u.name() + " | " + u.prn() + " | " + u.branch();
    }
    public BigDecimal availableAdvance(String actorId, String advanceId) {
        State s = repository.snapshot(); User actor = active(s, actorId); Advance a = s.advances.get(advanceId);
        if (a == null || (actor.role() != Role.ADMIN && !a.studentId().equals(actorId))) throw new DomainException("Advance not available to this account.");
        return available(s, a, "");
    }
    private static BigDecimal available(State s, Advance a, String excludeClaim) {
        BigDecimal used = s.claims.values().stream().filter(c -> c.reservesAdvance() && c.advanceId().equals(a.id()) && !c.id().equals(excludeClaim))
                .map(Claim::advanceApplied).reduce(Money.ZERO, BigDecimal::add);
        return a.amount().subtract(a.returned()).subtract(used);
    }
    public void linkAdvance(String actorId, String claimId, String advanceId, String amount) {
        BigDecimal value = Money.parse(amount);
        repository.update(s -> { Claim c = editable(s, actorId, claimId);
            if (advanceId.isBlank() || value.signum() == 0) { c.advance("", Money.ZERO); }
            else { Advance a = s.advances.get(advanceId);
                if (a == null || !a.studentId().equals(c.studentId())) throw new DomainException("Select an advance for this student.");
                if (value.compareTo(available(s, a, c.id())) > 0) throw new DomainException("Insufficient unallocated advance balance.");
                c.advance(a.id(), value);
            }
            c.history(now(), actorId, "Updated advance allocation to " + Money.format(c.advanceApplied()) + " (reserved on submission)."); return null;
        });
    }
    public void submit(String actorId, String claimId) {
        repository.update(s -> { Claim c = editable(s, actorId, claimId); User student = active(s, c.studentId());
            if (student.beneficiary() == null) throw new DomainException("Save a student beneficiary before submitting.");
            if (c.items().isEmpty()) throw new DomainException("Add at least one expense.");
            if (c.items().stream().anyMatch(i -> i.evidence().isEmpty())) throw new DomainException("Each expense needs at least one receipt or UPI evidence file.");
            if (!c.advanceId().isEmpty() && c.advanceApplied().compareTo(available(s, s.advances.get(c.advanceId()), c.id())) > 0)
                throw new DomainException("Advance balance changed. Adjust the allocation before submitting.");
            c.beneficiary(student.beneficiary()); c.incrementRevision(); c.status(ClaimStatus.SUBMITTED);
            c.archiveSubmission(now());
            User old = s.users.get(c.reviewerId());
            if (old == null || old.suspended() || !inScope(old, student) || old.id().equals(student.id())) {
                c.assign(s.users.values().stream().filter(u -> !u.suspended() && u.role() == Role.APPROVER &&
                        !u.id().equals(student.id()) && inScope(u, student)).map(User::id).findFirst().orElse(""));
            }
            c.history(now(), actorId, "Submitted revision " + c.revision() + " for " + Money.format(c.claimedTotal()) + ". Reviewer: " + (c.reviewerId().isEmpty() ? "unassigned" : c.reviewerId()));
            String body = summary(s, c);
            notify(s, student.id(), "Claim " + c.id() + " submitted (revision " + c.revision() + ")", body);
            notifyAdmins(s, "New submitted claim " + c.id(), body);
            if (!c.reviewerId().isEmpty()) notify(s, c.reviewerId(), "Claim assigned " + c.id(), body);
            audit(s, actorId, "Submitted " + c.id() + " revision " + c.revision()); return null;
        });
    }
    public void withdrawClaim(String actorId, String claimId) {
        repository.update(s -> { User u = active(s, actorId); Claim c = find(s, claimId);
            if (!(u.role() == Role.ADMIN || c.studentId().equals(actorId))) throw new DomainException("You cannot withdraw this claim.");
            if (c.status() == ClaimStatus.APPROVED || c.status() == ClaimStatus.REJECTED || c.status() == ClaimStatus.WITHDRAWN)
                throw new DomainException("This claim cannot be withdrawn.");
            c.status(ClaimStatus.WITHDRAWN); c.history(now(), actorId, "Withdrawn; advance reservation released.");
            notifyAdmins(s, "Claim withdrawn " + c.id(), "Advance reservation released."); audit(s, actorId, "Withdrew " + c.id()); return null;
        });
    }
    public void assign(String actorId, String claimId, String reviewerId) {
        repository.update(s -> { admin(s, actorId); Claim c = find(s, claimId); User reviewer = active(s, reviewerId);
            if (c.status() != ClaimStatus.SUBMITTED && c.status() != ClaimStatus.UNDER_REVIEW && c.status() != ClaimStatus.CHANGES_REQUESTED)
                throw new DomainException("Assign a reviewer to a submitted/review/correction claim.");
            if (reviewer.id().equals(c.studentId()) || !inScope(reviewer, s.users.get(c.studentId()))) throw new DomainException("Reviewer is the claimant or outside the branch scope.");
            c.assign(reviewerId); c.history(now(), actorId, "Assigned reviewer " + reviewerId);
            notify(s, reviewerId, "Claim assigned " + c.id(), summary(s, c)); audit(s, actorId, "Assigned " + c.id() + " to " + reviewerId); return null;
        });
    }
    public void beginReview(String actorId, String claimId) {
        repository.update(s -> { Claim c = reviewable(s, actorId, claimId); c.status(ClaimStatus.UNDER_REVIEW);
            c.history(now(), actorId, "Review started."); return null; });
    }
    public void reviewDecision(String actorId, String claimId, boolean reject, String reason) {
        String why = required(reason, "Review reason", 1000);
        repository.update(s -> { Claim c = reviewable(s, actorId, claimId);
            c.status(reject ? ClaimStatus.REJECTED : ClaimStatus.CHANGES_REQUESTED);
            c.history(now(), actorId, c.status() + ": " + why);
            notify(s, c.studentId(), "Claim " + c.id() + " " + c.status(), why);
            audit(s, actorId, c.id() + " " + c.status() + ": " + why); return null;
        });
    }
    public List<String> warnings(String actorId, String claimId) { claim(actorId, claimId); State s = repository.snapshot(); return warnings(s, find(s, claimId)); }
    private static List<String> warnings(State s, Claim c) {
        Set<String> warnings = new LinkedHashSet<>();
        for (ExpenseItem item : c.items()) for (ExpenseEvidence e : item.evidence()) {
            for (String warning : e.warnings()) warnings.add(item.id() + ": " + warning);
            for (Claim other : s.claims.values()) {
                if (other.status() == ClaimStatus.REJECTED || other.status() == ClaimStatus.WITHDRAWN) continue;
                for (ExpenseItem oi : other.items()) {
                    if (other.id().equals(c.id()) && oi.id().equals(item.id())) continue;
                    for (ExpenseEvidence oe : oi.evidence()) {
                        if (e.sha256().equals(oe.sha256())) warnings.add(item.id() + ": identical attachment exists on another expense; manual verification required.");
                        if (!e.reference().isBlank() && e.reference().equalsIgnoreCase(oe.reference()) && e.getClass() == oe.getClass())
                            warnings.add(item.id() + ": reference number is reused on another expense; verify before approval.");
                    }
                    if (oi.vendor().equalsIgnoreCase(item.vendor()) && oi.date().equals(item.date()) && oi.claimed().compareTo(item.claimed()) == 0)
                        warnings.add(item.id() + ": another expense has the same vendor, date and amount; possible duplicate.");
                }
            }
        }
        return List.copyOf(warnings);
    }
    public void approve(String actorId, String claimId, Map<String, BigDecimal> amounts, String reason, String warningResolution) {
        repository.update(s -> { Claim c = reviewable(s, actorId, claimId);
            if (amounts.size() != c.items().size() || c.items().stream().anyMatch(i -> !amounts.containsKey(i.id()))) throw new DomainException("Provide an approval amount for every expense.");
            boolean reduced = false; BigDecimal total = Money.ZERO;
            for (ExpenseItem i : c.items()) {
                BigDecimal value = Money.parse(amounts.get(i.id()).toPlainString());
                i.approve(value); total = total.add(value); reduced |= value.compareTo(i.claimed()) < 0;
            }
            if (total.signum() == 0) throw new DomainException("Use Reject if no expense amount is approved.");
            String note = reason == null ? "" : reason.trim();
            if (reduced) note = required(reason, "Reason for reduced approval", 1000);
            List<String> warnings = warnings(s, c);
            if (!warnings.isEmpty()) note += " | Warning resolution: " + required(warningResolution, "Documented resolution of evidence warnings", 1000);
            c.status(ClaimStatus.APPROVED); c.history(now(), actorId, "Approved " + Money.format(total) + ". " + note);
            notify(s, c.studentId(), "Claim approved " + c.id(), summary(s, c) + "\nApproval is not a payment. " + note);
            notifyAdmins(s, "Settlement required " + c.id(), summary(s, c)); audit(s, actorId, "Approved " + c.id() + ": " + Money.format(total)); return null;
        });
    }
    public String issueAdvance(String actorId, String studentId, String purpose, String amount) {
        BigDecimal value = Money.positive(amount); String p = required(purpose, "Advance purpose", 300);
        return repository.update(s -> { admin(s, actorId); User student = active(s, studentId);
            if (student.role() != Role.STUDENT && student.role() != Role.ADMIN) throw new DomainException("Choose a student beneficiary.");
            String id = s.next("A"); s.advances.put(id, new Advance(id, studentId, p, value, now()));
            notify(s, studentId, "Advance recorded " + id, Money.format(value) + "\nPurpose: " + p + "\nThis is a record of an external/simulated payment.");
            audit(s, actorId, "Recorded advance " + id + " for " + studentId + ": " + Money.format(value)); return id;
        });
    }
    public List<Advance> advances(String actorId) {
        State s = repository.snapshot(); User u = active(s, actorId);
        return s.advances.values().stream().filter(a -> u.role() == Role.ADMIN || a.studentId().equals(actorId)).toList();
    }
    private static void uniqueReference(State s, String reference) {
        boolean used = s.claims.values().stream().flatMap(c -> c.payments().stream()).anyMatch(p -> p.reference().equalsIgnoreCase(reference)) ||
                s.advances.values().stream().flatMap(a -> a.returns().stream()).anyMatch(p -> p.reference().equalsIgnoreCase(reference));
        if (used) throw new DomainException("This payment reference has already been recorded. Use a unique transaction reference.");
    }
    public void recordPayment(String actorId, String claimId, String amount, String reference) {
        BigDecimal value = Money.positive(amount); String ref = required(reference, "Payment reference", 100);
        repository.update(s -> { admin(s, actorId); Claim c = find(s, claimId);
            if (c.status() != ClaimStatus.APPROVED) throw new DomainException("Approve the claim before recording settlement.");
            if (value.compareTo(c.remaining()) > 0) throw new DomainException("Payment exceeds the remaining settlement balance.");
            uniqueReference(s, ref);
            PaymentKind kind = c.netSettlement().signum() < 0 ? PaymentKind.ADVANCE_RETURN : PaymentKind.REIMBURSEMENT;
            c.payment(new Payment(s.next("P"), kind, value, ref, now(), actorId, c.beneficiary()));
            c.history(now(), actorId, kind + " recorded: " + Money.format(value) + ", reference " + ref);
            notify(s, c.studentId(), "Settlement payment recorded " + c.id(), kind + ": " + Money.format(value) +
                    "\nReference: " + ref + "\n" + c.settlementLabel() + "\nRecorded by finance; not independent bank verification.");
            audit(s, actorId, "Recorded " + kind + " for " + c.id() + ": " + Money.format(value)); return null;
        });
    }
    public void returnUnallocatedAdvance(String actorId, String advanceId, String amount, String reference) {
        BigDecimal value = Money.positive(amount); String ref = required(reference, "Return reference", 100);
        repository.update(s -> { admin(s, actorId); Advance a = s.advances.get(advanceId);
            if (a == null) throw new DomainException("Advance not found.");
            if (value.compareTo(available(s, a, "")) > 0) throw new DomainException("Return exceeds the unallocated advance. Use claim settlement for allocated returns.");
            uniqueReference(s, ref); a.returned(new AdvanceReturn(s.next("P"), value, ref, now()));
            notify(s, a.studentId(), "Unused advance returned " + a.id(), Money.format(value) + "\nReference: " + ref);
            audit(s, actorId, "Returned unallocated advance " + a.id() + ": " + Money.format(value)); return null;
        });
    }
    public List<Notification> inbox(String actorId) {
        State s = repository.snapshot(); active(s, actorId);
        return s.notifications.values().stream().filter(n -> n.userId().equals(actorId)).toList();
    }
    public void readNotification(String actorId, String notificationId) {
        repository.update(s -> { active(s, actorId); Notification n = s.notifications.get(notificationId);
            if (n == null || !n.userId().equals(actorId)) throw new DomainException("Notification not found."); n.markRead(); return null;
        });
    }
    public List<Notification> notificationLog(String actorId) {
        State s = repository.snapshot(); admin(s, actorId); return List.copyOf(s.notifications.values());
    }
    public void retryNotification(String actorId, String id) {
        repository.update(s -> { admin(s, actorId); Notification n = s.notifications.get(id);
            if (n == null || n.status() != MailStatus.FAILED) throw new DomainException("Only failed local notifications can be retried."); n.retry(); return null;
        });
    }
    public List<History> auditLog(String actorId) { State s = repository.snapshot(); admin(s, actorId); return List.copyOf(s.audit); }
    private static String summary(State s, Claim c) {
        User student = s.users.get(c.studentId()); StringBuilder out = new StringBuilder();
        out.append(c.id()).append(" - ").append(c.title()).append("\nStudent: ").append(student.name()).append(" | ").append(student.prn())
                .append("\nBranch: ").append(student.branch()).append("\nPurpose: ").append(c.purpose()).append("\nStatus: ").append(c.status()).append("\n");
        for (ExpenseItem i : c.items()) out.append(i.vendor()).append(" | ").append(i.category()).append(" | ").append(Money.format(i.claimed())).append("\n");
        out.append("Claimed: ").append(Money.format(c.claimedTotal())).append("\nAdvance linked: ").append(Money.format(c.advanceApplied()));
        if (c.status() == ClaimStatus.APPROVED) out.append("\nApproved: ").append(Money.format(c.approvedTotal())).append("\n").append(c.settlementLabel());
        return out.toString();
    }
    public void createDemo(String actorId, String password) {
        String hash = Security.hashPassword(password);
        repository.update(s -> { admin(s, actorId);
            if (s.demoCreated || s.users.values().stream().anyMatch(u -> u.email().equals("student.demo@example.test") || u.email().equals("approver.demo@example.test")))
                throw new DomainException("Demo accounts already exist; no records were overwritten.");
            String sid = s.next("U"), rid = s.next("U");
            User student = new User(sid, "Demo Student", "student.demo@example.test", "DEMO-STUDENT-01", "B", Branch.IT, Role.STUDENT, hash);
            student.beneficiary(new Beneficiary(s.next("B"), "Demo Student", "Fictional Demo Bank", security.encrypt("DEMO-12345678"), "****5678", "DEMO0000001"));
            s.users.put(sid, student); s.users.put(rid, new User(rid, "Demo Faculty", "approver.demo@example.test", "DEMO-FACULTY-01", "Faculty", Branch.IT, Role.APPROVER, hash));
            String aid = s.next("A"); s.advances.put(aid, new Advance(aid, sid, "Demo project materials", Money.parse("2000"), now()));
            notify(s, sid, "Demo account ready", "Fictional beneficiary and INR 2000 advance are available. No actual payment was made.");
            s.demoCreated = true; audit(s, actorId, "Created fictional demo accounts and advance."); return null;
        });
    }
}
