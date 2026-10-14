package reimora;

import java.nio.file.Path;

public interface ReportExporter {
    Path exportClaims(String actorId, String search);
}
