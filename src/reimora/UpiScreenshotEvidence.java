package reimora;

import java.util.*;

public final class UpiScreenshotEvidence extends ExpenseEvidence {
    private static final long serialVersionUID = 1L;
    private final String paymentStatus;
    public UpiScreenshotEvidence(String id, String original, String stored, String hash, String reference, String status) {
        super(id, original, stored, hash, reference); paymentStatus = status;
    }
    public String paymentStatus() { return paymentStatus; }
    @Override public String kind() { return "UPI screenshot (" + paymentStatus + ")"; }
    @Override public List<String> warnings() {
        List<String> result = new ArrayList<>();
        if (reference().isBlank()) result.add("UPI reference is missing.");
        if (!paymentStatus.equals("SUCCESS")) result.add("UPI status is " + paymentStatus + "; it is not successful-payment evidence.");
        return result;
    }
}
