package reimora;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** All monetary amounts use exact decimals with two fractional places. */
public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private Money() { }
    public static BigDecimal parse(String input) {
        try {
            BigDecimal n = new BigDecimal(input.trim()).setScale(2, RoundingMode.UNNECESSARY);
            if (n.signum() < 0 || n.compareTo(new BigDecimal("100000000.00")) > 0)
                throw new IllegalArgumentException();
            return n;
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new DomainException("Enter a non-negative amount up to 100000000 with at most two decimal places.");
        }
    }
    public static BigDecimal positive(String input) {
        BigDecimal n = parse(input);
        if (n.signum() == 0) throw new DomainException("Amount must be greater than zero.");
        return n;
    }
    public static String format(BigDecimal amount) { return "INR " + amount.setScale(2).toPlainString(); }
}
