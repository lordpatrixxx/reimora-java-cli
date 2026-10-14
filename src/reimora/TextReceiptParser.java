package reimora;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static reimora.Types.Category;

/** A deterministic key:value parser, deliberately labelled as non-AI. */
public final class TextReceiptParser implements ReceiptParser {
    @Override public Map<String, String> extract(Path source) {
        try {
            if (!source.toString().toLowerCase(Locale.ROOT).endsWith(".txt") || Files.size(source) > 1024 * 1024)
                throw new DomainException("Text import accepts .txt receipts up to 1 MB. Enter image/PDF details manually.");
            Map<String, String> values = new LinkedHashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon < 1) continue;
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    if (Set.of("vendor", "amount", "date", "reference", "items", "purpose").contains(key))
                        values.put(key, line.substring(colon + 1).trim().replaceAll("\\p{Cntrl}", ""));
                }
            }
            return values;
        } catch (IOException e) { throw new DomainException("Cannot import text receipt: " + e.getMessage(), e); }
    }
    public Category suggest(Map<String, String> fields) {
        String items = (fields.getOrDefault("items", "") + " " + fields.getOrDefault("purpose", "")).toLowerCase(Locale.ROOT);
        if (items.matches(".*\\b(print|printing|photocopy|xerox)\\b.*")) return Category.PRINTING;
        if (items.matches(".*\\b(pen|pens|notebook|notebooks|stationery|chart paper)\\b.*")) return Category.STATIONERY;
        if (items.matches(".*\\b(tea|coffee|food|snacks|refreshments|meal)\\b.*")) return Category.REFRESHMENT;
        if (items.matches(".*\\b(rent|rental)\\b.*")) return Category.RENT;
        if (items.matches(".*\\b(bus|train|travel|taxi|fare)\\b.*")) return Category.TRAVEL;
        if (items.matches(".*\\b(arduino|sensor|equipment|projector)\\b.*")) return Category.EQUIPMENT;
        if (items.matches(".*\\b(design|foam|fabric|prototype)\\b.*")) return Category.DESIGN_MATERIAL;
        return Category.OTHER;
    }
}
