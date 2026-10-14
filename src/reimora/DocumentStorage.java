package reimora;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Copies evidence; never edits or deletes the student's original file. */
public final class DocumentStorage {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path directory;
    public DocumentStorage(Path directory) throws IOException { this.directory = directory.toAbsolutePath().normalize(); Files.createDirectories(directory); }
    public ExpenseEvidence importFile(Path source, boolean upi, String reference, String status) {
        String ref = reference.trim();
        if (ref.length() > 100 || ref.chars().anyMatch(Character::isISOControl)) throw new DomainException("Invalid reference number.");
        if (upi && !Set.of("SUCCESS", "PENDING", "FAILED", "UNKNOWN").contains(status)) throw new DomainException("Invalid UPI status.");
        try {
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_BYTES || Files.size(source) == 0)
                throw new DomainException("Choose a non-empty regular file up to 10 MB.");
            String name = source.getFileName().toString(); int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            byte[] bytes = Files.readAllBytes(source);
            if (bytes.length > MAX_BYTES) throw new DomainException("File exceeds 10 MB.");
            boolean valid = switch (extension) {
                case "txt" -> !new String(bytes, StandardCharsets.UTF_8).contains("\u0000");
                case "pdf" -> starts(bytes, new byte[]{37, 80, 68, 70, 45});
                case "png" -> starts(bytes, new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
                case "jpg", "jpeg" -> starts(bytes, new byte[]{(byte)255, (byte)216, (byte)255});
                case "webp" -> bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") &&
                        new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
                default -> false;
            };
            if (!valid) throw new DomainException("Supported files: TXT, PDF, PNG, JPG, WebP. File header must match its extension.");
            String id = UUID.randomUUID().toString(), stored = id + "." + extension;
            Files.write(directory.resolve(stored), bytes, StandardOpenOption.CREATE_NEW);
            String hash = Security.sha256(bytes);
            return upi ? new UpiScreenshotEvidence(id, name, stored, hash, ref, status) : new ReceiptEvidence(id, name, stored, hash, ref);
        } catch (IOException e) { throw new DomainException("Cannot read/copy evidence: " + e.getMessage(), e); }
    }
    private boolean starts(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (source[i] != prefix[i]) return false;
        return true;
    }
    public Path path(ExpenseEvidence evidence) {
        Path path = directory.resolve(evidence.storedName()).normalize();
        if (!path.getParent().equals(directory)) throw new DomainException("Invalid stored document path.");
        return path;
    }
    /** Only used to roll back a brand-new imported copy when saving its metadata fails. */
    public void discardNewCopy(ExpenseEvidence evidence) {
        try { Files.deleteIfExists(path(evidence)); } catch (IOException ignored) { }
    }
}
