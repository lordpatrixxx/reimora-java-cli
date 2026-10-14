package reimora;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

final class Terminal {
    static final class EndOfInput extends RuntimeException { private static final long serialVersionUID = 1L; }
    private final Console console = System.console();
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
    private boolean passwordWarningShown;
    String line(String prompt) {
        String value;
        try {
            if (console != null) value = console.readLine("%s", prompt);
            else { System.out.print(prompt); System.out.flush(); value = input.readLine(); }
        } catch (IOException e) { throw new DomainException("Input error: " + e.getMessage(), e); }
        if (value == null) throw new EndOfInput();
        return value.trim();
    }
    String password(String prompt) {
        if (console == null) {
            if (!passwordWarningShown) { System.out.println("[Password input is visible in this terminal; a normal Command Prompt supports hidden entry.]"); passwordWarningShown = true; }
            return line(prompt);
        }
        char[] password = console.readPassword("%s", prompt);
        if (password == null) throw new EndOfInput();
        String value = new String(password); Arrays.fill(password, '\0'); return value;
    }
    String newPassword() {
        String password = password("Choose password (8+ characters): ");
        if (!password.equals(password("Confirm password: "))) throw new DomainException("Passwords do not match.");
        return password;
    }
    int choice(String prompt, int maximum) {
        while (true) {
            try { int n = Integer.parseInt(line(prompt)); if (n >= 0 && n <= maximum) return n; }
            catch (NumberFormatException ignored) { }
            System.out.println("Enter a number from 0 to " + maximum + ".");
        }
    }
    boolean yes(String prompt) { return line(prompt + " [y/N]: ").equalsIgnoreCase("y"); }
    String id(String prompt) { return line(prompt).toUpperCase(java.util.Locale.ROOT); }
    String withDefault(String prompt, String value) {
        String input = line(prompt + (value.isBlank() ? "" : " [" + value + "]") + ": ");
        return input.isBlank() ? value : input;
    }
    <T> T pick(String label, T[] values) {
        for (int i = 0; i < values.length; i++) System.out.println("  " + (i + 1) + ". " + values[i]);
        int index = choice(label + " (0 = cancel): ", values.length);
        return index == 0 ? null : values[index - 1];
    }
    LocalDate date(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw new DomainException("Use a valid date in YYYY-MM-DD format."); }
    }
    String path(String prompt) {
        String value = line(prompt);
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
            value = value.substring(1, value.length() - 1);
        return value;
    }
}
