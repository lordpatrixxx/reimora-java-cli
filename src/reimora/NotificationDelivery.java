package reimora;

import java.io.IOException;
import reimora.Models.*;

public interface NotificationDelivery {
    void deliver(Notification notification, User recipient) throws IOException;
}
