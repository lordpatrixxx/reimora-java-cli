package reimora;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import reimora.Models.*;
import static reimora.Types.*;

public final class Reports implements ReportExporter {
    private final ReimoraService service;
    private final Path directory;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));
    public Reports(ReimoraService service, Path directory) { this.service = service; this.directory = directory; }
    public static String time(Instant time) { return TIME.format(time) + " IST"; }
    public String claimText(String actorId, String claimId) {
        Claim c = service.claim(actorId, claimId); StringBuilder out = new StringBuilder();
        out.append("REIMORA - CLAIM SUMMARY\n").append("=".repeat(70)).append("\n")
                .append(c.id()).append(" | ").append(c.title()).append("\nStudent: ").append(service.studentLabel(actorId, claimId))
                .append("\nPurpose: ").append(c.purpose()).append("\nCreated: ").append(time(c.created()))
                .append("\nStatus: ").append(c.status()).append(" | Revision: ").append(c.revision())
                .append("\nReviewer: ").append(c.reviewerId().isEmpty() ? "Unassigned" : c.reviewerId()).append("\n\nEXPENSES\n");
        for (ExpenseItem i : c.items()) {
            out.append(i.id()).append(" | ").append(i.date()).append(" | ").append(i.vendor()).append(" | ").append(i.category())
                    .append("\n  Purpose: ").append(i.purpose()).append("\n  Claimed: ").append(Money.format(i.claimed()))
                    .append(" | Approved: ").append(c.status() == ClaimStatus.APPROVED ? Money.format(i.approved()) : "Pending decision").append("\n");
            for (ExpenseEvidence e : i.evidence()) out.append("  Evidence ").append(e.id()).append(" | ").append(e.kind())
                    .append(" | ").append(e.originalName()).append(" | Reference: ").append(e.reference()).append("\n");
        }
        out.append("\nClaimed total: ").append(Money.format(c.claimedTotal())).append("\nAdvance selected: ").append(c.advanceId()).append(" ").append(Money.format(c.advanceApplied()));
        if (c.status() == ClaimStatus.APPROVED) out.append("\nApproved total: ").append(Money.format(c.approvedTotal())).append("\nSettlement: ").append(c.settlementLabel());
        if (c.beneficiary() != null) out.append("\nBeneficiary snapshot: ").append(c.beneficiary().holder()).append(" | ").append(c.beneficiary().bank()).append(" | ").append(c.beneficiary().maskedAccount());
        out.append("\n\nPAYMENTS (manually recorded; not bank verified)\n");
        for (Payment p : c.payments()) out.append(time(p.at())).append(" | ").append(p.kind()).append(" | ").append(Money.format(p.amount())).append(" | ").append(p.reference()).append("\n");
        List<String> warnings = service.warnings(actorId, claimId);
        if (!warnings.isEmpty()) { out.append("\nEVIDENCE WARNINGS\n"); for (String w : warnings) out.append("- ").append(w).append("\n"); }
        if (c.revisions().size() > 1) {
            out.append("\nPRESERVED SUBMISSION REVISIONS\n");
            for (ClaimRevision r : c.revisions()) {
                out.append("Revision ").append(r.number()).append(" at ").append(time(r.submitted())).append("\n");
                for (ExpenseSnapshot i : r.expenses()) out.append("  ").append(i.id()).append(" | ").append(i.vendor()).append(" | ")
                        .append(Money.format(i.amount())).append(" | ").append(i.purpose()).append(" | evidence=").append(i.evidence().size()).append("\n");
            }
        }
        out.append("\nHISTORY\n");
        for (History h : c.history()) out.append(time(h.at())).append(" | ").append(h.actor()).append(" | ").append(h.message()).append("\n");
        return out.toString();
    }
    private Path output(String prefix, String extension) throws IOException {
        Files.createDirectories(directory);
        return directory.resolve(prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + extension).toAbsolutePath();
    }
    public Path exportClaim(String actorId, String claimId) {
        String text = claimText(actorId, claimId);
        try { Path path = output(claimId, ".txt"); Files.writeString(path, text, StandardCharsets.UTF_8); return path; }
        catch (IOException e) { throw new DomainException("Could not export claim: " + e.getMessage(), e); }
    }
    static String csv(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    @Override public Path exportClaims(String actorId, String search) {
        List<Claim> claims = service.claims(actorId, search);
        try {
            Path path = output("claims", ".csv");
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("Claim ID,Student,Title,Status,Claimed INR,Approved INR,Advance INR,Settlement,Created IST\n");
                for (Claim c : claims) out.write(String.join(",", csv(c.id()), csv(service.studentLabel(actorId, c.id())), csv(c.title()), csv(c.status().name()),
                        c.claimedTotal().toPlainString(), c.status() == ClaimStatus.APPROVED ? c.approvedTotal().toPlainString() : "",
                        c.advanceApplied().toPlainString(), csv(c.settlementLabel()), csv(time(c.created()))) + "\n");
            }
            return path;
        } catch (IOException e) { throw new DomainException("Could not export CSV: " + e.getMessage(), e); }
    }
    public String dashboard(String actorId) {
        List<Claim> claims = service.claims(actorId);
        BigDecimal claimed = Money.ZERO, approved = Money.ZERO, payable = Money.ZERO, returnable = Money.ZERO;
        Map<ClaimStatus, Integer> statuses = new EnumMap<>(ClaimStatus.class);
        Map<Category, BigDecimal> categories = new EnumMap<>(Category.class);
        for (Claim c : claims) {
            statuses.merge(c.status(), 1, Integer::sum);
            if (c.status() == ClaimStatus.WITHDRAWN || c.status() == ClaimStatus.REJECTED) continue;
            claimed = claimed.add(c.claimedTotal());
            for (ExpenseItem item : c.items()) categories.merge(item.category(), item.claimed(), BigDecimal::add);
            if (c.status() == ClaimStatus.APPROVED) {
                approved = approved.add(c.approvedTotal());
                if (c.netSettlement().signum() < 0) returnable = returnable.add(c.remaining());
                else payable = payable.add(c.remaining());
            }
        }
        StringBuilder out = new StringBuilder("\nYOUR AUTHORIZED CLAIM SUMMARY\n");
        out.append("Counts: ").append(statuses).append("\nActive claimed (includes drafts): ").append(Money.format(claimed))
                .append("\nApproved total: ").append(Money.format(approved)).append("\nReimbursements outstanding: ").append(Money.format(payable))
                .append("\nClaim-linked advance returns outstanding: ").append(Money.format(returnable)).append("\n");
        List<Advance> advances = service.advances(actorId);
        BigDecimal unallocated = Money.ZERO;
        for (Advance a : advances) unallocated = unallocated.add(service.availableAdvance(actorId, a.id()));
        if (service.user(actorId).role() != Role.APPROVER) out.append("Unallocated advances still held by students: ").append(Money.format(unallocated)).append("\n");
        out.append("Category totals (active claimed amounts):\n");
        for (Map.Entry<Category, BigDecimal> entry : categories.entrySet()) out.append("  ").append(entry.getKey()).append(": ").append(Money.format(entry.getValue())).append("\n");
        return out.toString();
    }
}
