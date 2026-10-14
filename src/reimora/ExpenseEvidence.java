package reimora;

import java.io.Serializable;
import java.util.List;

/** Shared evidence data; subclasses supply different validation behaviour. */
public abstract class ExpenseEvidence implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String id, originalName, storedName, sha256, reference;
    protected ExpenseEvidence(String id, String originalName, String storedName, String sha256, String reference) {
        this.id = id; this.originalName = originalName; this.storedName = storedName;
        this.sha256 = sha256; this.reference = reference;
    }
    public String id() { return id; } public String originalName() { return originalName; }
    public String storedName() { return storedName; } public String sha256() { return sha256; }
    public String reference() { return reference; }
    public abstract String kind();
    public abstract List<String> warnings();
}
