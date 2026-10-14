package reimora;

import java.io.Serializable;
import java.util.*;
import reimora.Models.*;

/** Repository aggregate; exposed only as detached snapshots to callers. */
final class State implements Serializable {
    private static final long serialVersionUID = 1L;
    int version = 1;
    long sequence;
    final Map<String, User> users = new LinkedHashMap<>();
    final Map<String, AccessRequest> requests = new LinkedHashMap<>();
    final Map<String, Claim> claims = new LinkedHashMap<>();
    final Map<String, Advance> advances = new LinkedHashMap<>();
    final Map<String, Notification> notifications = new LinkedHashMap<>();
    final List<History> audit = new ArrayList<>();
    boolean demoCreated;
    String next(String prefix) { return prefix + String.format("%05d", ++sequence); }
}
