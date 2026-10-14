package reimora;

public final class Types {
    private Types() { }
    public enum Role { PENDING, STUDENT, APPROVER, ADMIN }
    public enum RequestStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }
    public enum ClaimStatus { DRAFT, SUBMITTED, UNDER_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED, WITHDRAWN }
    public enum Category { STATIONERY, REFRESHMENT, RENT, DESIGN_MATERIAL, PRINTING, TRAVEL, EQUIPMENT, OTHER }
    public enum PaymentKind { REIMBURSEMENT, ADVANCE_RETURN }
    public enum MailStatus { QUEUED, LOCAL_SAVED, FAILED }
    public enum Branch {
        CIVIL("Civil Engineering"), CIVIL_RL("Civil Engineering (Regional Language)"),
        COMPUTER("Computer Engineering"), COMPUTER_RL("Computer Engineering (Regional Language)"),
        AIML("Computer Science and Engineering (AI and ML)"),
        ENTC("Electronics and Telecommunication Engineering"),
        VLSI("Electronics Engineering (VLSI Design & Technology)"),
        IT("Information Technology"), MECHANICAL("Mechanical Engineering");
        private final String label;
        Branch(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
