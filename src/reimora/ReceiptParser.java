package reimora;

import java.nio.file.Path;
import java.util.Map;

public interface ReceiptParser {
    Map<String, String> extract(Path source);
}
