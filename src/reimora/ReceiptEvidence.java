package reimora;

import java.util.List;

public final class ReceiptEvidence extends ExpenseEvidence {
    private static final long serialVersionUID = 1L;
    public ReceiptEvidence(String id, String original, String stored, String hash, String invoice) {
        super(id, original, stored, hash, invoice);
    }
    @Override public String kind() { return "Receipt"; }
    @Override public List<String> warnings() {
        return reference().isBlank() ? List.of("Receipt has no invoice/reference number; verify manually.") : List.of();
    }
}
