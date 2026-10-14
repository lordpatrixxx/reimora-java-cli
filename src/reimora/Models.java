package reimora;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static reimora.Types.*;

/** Domain objects. Mutation methods are package-private and used by the service. */
public final class Models {
    private Models() { }

    public static final class User implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, name, email, prn, division, passwordHash;
        private final Branch branch;
        private Role role;
        private boolean suspended, allBranches;
        private Beneficiary beneficiary;
        public User(String id, String name, String email, String prn, String division,
                    Branch branch, Role role, String passwordHash) {
            this.id = id; this.name = name; this.email = email; this.prn = prn;
            this.division = division; this.branch = branch; this.role = role; this.passwordHash = passwordHash;
        }
        public String id() { return id; } public String name() { return name; }
        public String email() { return email; } public String prn() { return prn; }
        public String division() { return division; } public Branch branch() { return branch; }
        public Role role() { return role; } public boolean suspended() { return suspended; }
        public boolean allBranches() { return allBranches; } public Beneficiary beneficiary() { return beneficiary; }
        boolean verifyPassword(String password) { return Security.matches(password, passwordHash); }
        void grant(Role role, boolean allBranches) { this.role = role; this.allBranches = allBranches; }
        void suspend(boolean value) { suspended = value; }
        void beneficiary(Beneficiary value) { beneficiary = value; }
    }

    public record Beneficiary(String id, String holder, String bank, String encryptedAccount,
                              String maskedAccount, String ifsc) implements Serializable { }

    public static final class AccessRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, userId, reason;
        private final Role requestedRole;
        private final Instant created;
        private RequestStatus status = RequestStatus.PENDING;
        private String decision = "";
        public AccessRequest(String id, String userId, Role role, String reason, Instant created) {
            this.id = id; this.userId = userId; this.requestedRole = role; this.reason = reason; this.created = created;
        }
        public String id() { return id; } public String userId() { return userId; }
        public Role requestedRole() { return requestedRole; } public String reason() { return reason; }
        public Instant created() { return created; } public RequestStatus status() { return status; }
        public String decision() { return decision; }
        void decide(RequestStatus status, String decision) { this.status = status; this.decision = decision; }
    }

    public static final class ExpenseItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, vendor, purpose;
        private final Category category;
        private final LocalDate date;
        private final BigDecimal claimed;
        private BigDecimal approved = Money.ZERO;
        private final List<ExpenseEvidence> evidence = new ArrayList<>();
        public ExpenseItem(String id, String vendor, String purpose, Category category, LocalDate date, BigDecimal amount) {
            this.id = id; this.vendor = vendor; this.purpose = purpose; this.category = category;
            this.date = date; this.claimed = amount;
        }
        public String id() { return id; } public String vendor() { return vendor; }
        public String purpose() { return purpose; } public Category category() { return category; }
        public LocalDate date() { return date; } public BigDecimal claimed() { return claimed; }
        public BigDecimal approved() { return approved; }
        public List<ExpenseEvidence> evidence() { return List.copyOf(evidence); }
        void attach(ExpenseEvidence item) { evidence.add(item); }
        void approve(BigDecimal amount) {
            if (amount.signum() < 0 || amount.compareTo(claimed) > 0)
                throw new DomainException("Approved amount must be between zero and the claimed amount.");
            approved = amount;
        }
    }

    public record History(Instant at, String actor, String message) implements Serializable { }
    public record Payment(String id, PaymentKind kind, BigDecimal amount, String reference,
                          Instant at, String actor, Beneficiary beneficiary) implements Serializable { }
    public record ExpenseSnapshot(String id, String vendor, String purpose, Category category, LocalDate date,
                                  BigDecimal amount, List<ExpenseEvidence> evidence) implements Serializable { }
    public record ClaimRevision(int number, Instant submitted, List<ExpenseSnapshot> expenses,
                                String advanceId, BigDecimal advanceApplied, Beneficiary beneficiary) implements Serializable { }

    public static final class Claim implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, studentId, title, purpose;
        private final Instant created;
        private ClaimStatus status = ClaimStatus.DRAFT;
        private final List<ExpenseItem> items = new ArrayList<>();
        private final List<History> history = new ArrayList<>();
        private final List<Payment> payments = new ArrayList<>();
        private final List<ClaimRevision> revisions = new ArrayList<>();
        private String reviewerId = "", advanceId = "";
        private BigDecimal advanceApplied = Money.ZERO;
        private Beneficiary beneficiary;
        private int revision;
        public Claim(String id, String studentId, String title, String purpose, Instant created) {
            this.id = id; this.studentId = studentId; this.title = title; this.purpose = purpose; this.created = created;
        }
        public String id() { return id; } public String studentId() { return studentId; }
        public String title() { return title; } public String purpose() { return purpose; }
        public Instant created() { return created; } public ClaimStatus status() { return status; }
        public String reviewerId() { return reviewerId; } public String advanceId() { return advanceId; }
        public BigDecimal advanceApplied() { return advanceApplied; } public Beneficiary beneficiary() { return beneficiary; }
        public int revision() { return revision; }
        public List<ExpenseItem> items() { return List.copyOf(items); }
        public List<History> history() { return List.copyOf(history); }
        public List<Payment> payments() { return List.copyOf(payments); }
        public List<ClaimRevision> revisions() { return List.copyOf(revisions); }
        public BigDecimal claimedTotal() { return items.stream().map(ExpenseItem::claimed).reduce(Money.ZERO, BigDecimal::add); }
        public BigDecimal approvedTotal() { return items.stream().map(ExpenseItem::approved).reduce(Money.ZERO, BigDecimal::add); }
        public BigDecimal netSettlement() { return approvedTotal().subtract(advanceApplied); }
        public BigDecimal paid(PaymentKind kind) { return payments.stream().filter(p -> p.kind() == kind).map(Payment::amount).reduce(Money.ZERO, BigDecimal::add); }
        public BigDecimal remaining() {
            BigDecimal net = netSettlement();
            return net.abs().subtract(paid(net.signum() < 0 ? PaymentKind.ADVANCE_RETURN : PaymentKind.REIMBURSEMENT));
        }
        public String settlementLabel() {
            if (status != ClaimStatus.APPROVED) return "Not approved";
            if (remaining().signum() == 0) return "SETTLED";
            return (netSettlement().signum() < 0 ? "RETURN DUE " : "PAYABLE ") + Money.format(remaining());
        }
        boolean editable() { return status == ClaimStatus.DRAFT || status == ClaimStatus.CHANGES_REQUESTED; }
        boolean reservesAdvance() { return status == ClaimStatus.SUBMITTED || status == ClaimStatus.UNDER_REVIEW ||
                status == ClaimStatus.CHANGES_REQUESTED || status == ClaimStatus.APPROVED; }
        void add(ExpenseItem item) { items.add(item); }
        void remove(String itemId) { if (!items.removeIf(i -> i.id().equals(itemId))) throw new DomainException("Expense not found."); }
        void status(ClaimStatus value) { status = value; }
        void assign(String reviewerId) { this.reviewerId = reviewerId; }
        void advance(String id, BigDecimal amount) { advanceId = id; advanceApplied = amount; }
        void beneficiary(Beneficiary value) { beneficiary = value; }
        void incrementRevision() { revision++; }
        void archiveSubmission(Instant submitted) {
            List<ExpenseSnapshot> snapshot = items.stream().map(i -> new ExpenseSnapshot(i.id(), i.vendor(), i.purpose(),
                    i.category(), i.date(), i.claimed(), i.evidence())).toList();
            revisions.add(new ClaimRevision(revision, submitted, snapshot, advanceId, advanceApplied, beneficiary));
        }
        void history(Instant at, String actor, String message) { history.add(new History(at, actor, message)); }
        void payment(Payment payment) { payments.add(payment); }
    }

    public record AdvanceReturn(String id, BigDecimal amount, String reference, Instant at) implements Serializable { }
    public static final class Advance implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, studentId, purpose;
        private final BigDecimal amount;
        private final Instant created;
        private final List<AdvanceReturn> returns = new ArrayList<>();
        public Advance(String id, String studentId, String purpose, BigDecimal amount, Instant created) {
            this.id = id; this.studentId = studentId; this.purpose = purpose; this.amount = amount; this.created = created;
        }
        public String id() { return id; } public String studentId() { return studentId; }
        public String purpose() { return purpose; } public BigDecimal amount() { return amount; }
        public Instant created() { return created; } public List<AdvanceReturn> returns() { return List.copyOf(returns); }
        public BigDecimal returned() { return returns.stream().map(AdvanceReturn::amount).reduce(Money.ZERO, BigDecimal::add); }
        void returned(AdvanceReturn payment) { returns.add(payment); }
    }

    public static final class Notification implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, userId, subject, body;
        private final Instant created;
        private MailStatus status = MailStatus.QUEUED;
        private int attempts;
        private boolean read;
        private String error = "";
        public Notification(String id, String userId, String subject, String body, Instant created) {
            this.id = id; this.userId = userId; this.subject = subject; this.body = body; this.created = created;
        }
        public String id() { return id; } public String userId() { return userId; }
        public String subject() { return subject; } public String body() { return body; }
        public Instant created() { return created; } public MailStatus status() { return status; }
        public boolean read() { return read; } public int attempts() { return attempts; }
        public String error() { return error; }
        void markRead() { read = true; }
        void result(boolean success, String error) { attempts++; status = success ? MailStatus.LOCAL_SAVED : MailStatus.FAILED; this.error = error; }
        void retry() { status = MailStatus.QUEUED; error = ""; }
    }
}
