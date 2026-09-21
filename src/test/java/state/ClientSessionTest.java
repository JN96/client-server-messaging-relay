package state;

import com.relay.config.Constants;
import com.relay.protocol.Message;
import com.relay.protocol.MessageType;
import com.relay.state.ClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientSessionTest {

    private ClientSession session;

    @BeforeEach
    public void setup() {
        session = new ClientSession();
    }

    @Test
    @DisplayName("Should queue messages up to the mailbox capacity limit")
    void testQueueCapacityLimit() {
        // add 100 message to the queue
        for (int i = 0; i < Constants.MAX_MAILBOX_SIZE; i++) {
            Message msg = new Message(MessageType.SEND, "msg-" + i, "James", "Ruth", "Payload", null, null);
            assertTrue(session.queueMessage(msg), "Message " + i + " should be queued successfully");
        }

        // try to add the 101st message to the queue
        Message overflowMsg = new Message(MessageType.SEND, "overflow", "James", "Ruth", "Payload", null, null);
        assertFalse(session.queueMessage(overflowMsg), "101st message should be rejected because mailbox is full");
    }

    @Test
    @DisplayName("Should correctly mark and remove unacknowledged messages")
    void testUnacknowledgedMessageLifecycle() {
        Message message = new Message(MessageType.DELIVERED, "123", "James", "Ruth", "Hello", null, null);

        session.markUnacknowledged(message);
        assertEquals(1, session.getUnacknowledgedMessages().size());
        assertTrue(session.getUnacknowledgedMessages().containsKey("123"));

        session.acknowledgeMessage("123");
        assertTrue(session.getUnacknowledgedMessages().isEmpty(), "Acknowledged messages should be removed from the map");
    }

    @Test
    @DisplayName("Should accurately reflect connection state when bound and cleared")
    void testConnectionState() {
        assertFalse(session.isConnected());

        PrintWriter mockWriter = new PrintWriter(new StringWriter());
        session.setConnection(mockWriter);

        assertTrue(session.isConnected());
        assertEquals(mockWriter, session.getConnection());

        session.clearConnection(mockWriter);
        assertFalse(session.isConnected(), "Connection should evaluate to false after clearConnection is called");
    }

    @Test
    @DisplayName("Should not clear a newer connection when a stale/old connection reference is cleared")
    void testClearConnectionIgnoresStaleReference() {
        PrintWriter oldWriter = new PrintWriter(new StringWriter());
        PrintWriter newWriter = new PrintWriter(new StringWriter());

        session.setConnection(oldWriter);
        session.setConnection(newWriter); // client reconnected before the old handler noticed it dropped

        session.clearConnection(oldWriter); // the old handler's delayed cleanup should be a no-op

        assertTrue(session.isConnected(), "The newer connection must survive a stale clearConnection call");
        assertEquals(newWriter, session.getConnection());
    }
}
