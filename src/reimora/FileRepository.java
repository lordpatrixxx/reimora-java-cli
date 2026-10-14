package reimora;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.function.Function;

/** Copy-on-write transactions: a failed operation never changes live state. */
final class FileRepository implements Repository {
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private State state;
    FileRepository(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("state.bin");
        channel = FileChannel.open(directory.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (OverlappingFileLockException e) { channel.close(); throw new IOException("Reimora is already open for this data folder."); }
        if (acquired == null) { channel.close(); throw new IOException("Reimora is already open for this data folder."); }
        lock = acquired;
        try {
            state = Files.exists(file) ? decode(Files.readAllBytes(file)) : new State();
            if (state.version != 1) throw new IOException("Unsupported data version.");
        } catch (Exception e) { close(); throw new IOException("Cannot read saved data. Existing files were preserved. Restore a known-good backup. " + e.getMessage(), e); }
    }
    private static byte[] encode(State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(state); }
        return bytes.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "maxdepth=60;maxrefs=300000;maxbytes=52428800;reimora.*;java.base/*;!*"));
            return (State) in.readObject();
        }
    }
    private State copy(State value) {
        try { return decode(encode(value)); }
        catch (IOException | ClassNotFoundException e) { throw new DomainException("Could not copy application data.", e); }
    }
    @Override public synchronized State snapshot() { return copy(state); }
    @Override public synchronized <T> T update(Function<State, T> operation) {
        State candidate = copy(state);
        T result = operation.apply(candidate);
        Path temp = file.resolveSibling("state.bin.tmp");
        try {
            byte[] bytes = encode(candidate);
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            if (Files.exists(file)) Files.copy(file, file.resolveSibling("state.bin.bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            state = candidate;
            return result;
        } catch (IOException e) { throw new DomainException("Could not save changes. Operation was not committed: " + e.getMessage(), e); }
    }
    @Override public void close() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
    }
}
