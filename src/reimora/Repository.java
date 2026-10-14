package reimora;

import java.util.function.Function;

/** Allows domain services to depend on a storage contract, not a file format. */
interface Repository extends AutoCloseable {
    State snapshot();
    <T> T update(Function<State, T> operation);
    @Override void close();
}
