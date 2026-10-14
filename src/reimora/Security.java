package reimora;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Password hashing and local beneficiary encryption; no external dependencies. */
public final class Security {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000;
    private final byte[] key;
    public Security(Path dataDirectory) throws Exception {
        Path keyFile = dataDirectory.resolve("master.key");
        if (Files.exists(keyFile)) key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        else {
            if (Files.exists(dataDirectory.resolve("state.bin")))
                throw new DomainException("master.key is missing. Restore it with your data backup; do not reset existing data.");
            key = new byte[32]; RANDOM.nextBytes(key);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardOpenOption.CREATE_NEW);
        }
        if (key.length != 32) throw new DomainException("Invalid local encryption key.");
    }
    public static String hashPassword(String password) {
        if (password.length() < 8 || password.length() > 128)
            throw new DomainException("Password must contain 8 to 128 characters.");
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new DomainException("Password hashing unavailable.", e); }
        finally { spec.clearPassword(); }
    }
    public static boolean matches(String password, String encoded) {
        try {
            String[] parts = encoded.split(":");
            int rounds = Integer.parseInt(parts[0]);
            if (rounds < 100_000 || rounds > 2_000_000) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]),
                    derive(password, Base64.getDecoder().decode(parts[1]), rounds));
        } catch (RuntimeException e) { return false; }
    }
    public String encrypt(String text) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new DomainException("Could not protect beneficiary details.", e); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
