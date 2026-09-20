package network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.Constants;
import com.relay.network.ClientHandler;
import com.relay.protocol.Message;
import com.relay.protocol.MessageType;
import com.relay.state.ClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientHandlerTest {

    private ConcurrentHashMap<String, ClientSession> registeredClients;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registeredClients = new ConcurrentHashMap<>();
        objectMapper = new ObjectMapper();
    }

    /**
     * Helper method to simulate a client connection with specific string input.
     * Returns the server's raw string output.
     */
    private String runHandlerWithInput(String input) throws Exception {
        Socket mockSocket = mock(Socket.class);
        ByteArrayInputStream inStream = new ByteArrayInputStream(input.getBytes());
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();

        when(mockSocket.getInputStream()).thenReturn(inStream);
        when(mockSocket.getOutputStream()).thenReturn(outStream);

        ClientHandler handler = new ClientHandler(mockSocket, registeredClients);
        handler.run();

        return outStream.toString();
    }

    @Test
    @DisplayName("handleRegister: Should successfully register a new client session")
    void testHandleRegister() throws Exception {
        Message regMsg = new Message(MessageType.REGISTER, "1", "James", null, null, null, null);
        runHandlerWithInput(objectMapper.writeValueAsString(regMsg) + "\n");

        assertTrue(registeredClients.containsKey("James"), "James should be added to the registered clients map");
    }

    @Test
    @DisplayName("handleRegister: Should reject registration when server is at max capacity")
    void testHandleRegisterMaxCapacity() throws Exception {
        // fill the registered clients map to its absolute limit
        for (int i = 0; i < Constants.MAX_REGISTERED_USERS; i++) {
            registeredClients.put("DummyUser" + i, new ClientSession());
        }

        // attempt to register a new user
        Message regMsg = new Message(MessageType.REGISTER, "99", "OverflowUser", null, null, null, null);
        String output = runHandlerWithInput(objectMapper.writeValueAsString(regMsg) + "\n");

        // verify the rejection and the ERROR payload
        assertFalse(registeredClients.containsKey("OverflowUser"), "OverflowUser should not be added to the map");
        assertTrue(output.contains("\"type\":\"ERROR\""), "Handler should return an ERROR payload");
        assertTrue(output.contains("maximum capacity"), "Error reason should mention capacity limits");    }

    @Test
    @DisplayName("handleSend: Should queue message and return RECEIPT when recipient is offline")
    void testHandleSendOfflineRecipient() throws Exception {
        // register Ruth as an offline client (no active stream attached)
        registeredClients.put("Ruth", new ClientSession());

        Message regMsg = new Message(MessageType.REGISTER, "1", "James", null, null, null, null);
        Message sendMsg = new Message(MessageType.SEND, "2", "James", "Ruth", "Hello Ruth", null, null);

        String input = objectMapper.writeValueAsString(regMsg) + "\n" +
                objectMapper.writeValueAsString(sendMsg) + "\n";

        String output = runHandlerWithInput(input);

        // verify the message was queued
        ClientSession ruthSession = registeredClients.get("Ruth");
        Message queuedMsg = ruthSession.pollPendingMessage();
        assertNotNull(queuedMsg, "Ruth should have a queued message");
        assertEquals("Hello Ruth", queuedMsg.getPayload(), "Payload should match the sent message");

        // verify the sender received a RECEIPT confirmation
        assertTrue(output.contains("\"type\":\"RECEIPT\""), "Handler should return a RECEIPT payload");
        assertTrue(output.contains("queued"), "Receipt reason should mention the message was queued");
    }

    @Test
    @DisplayName("handleSend: Should return ERROR when offline recipient's mailbox is full")
    void testHandleSendMailboxFull() throws Exception {
        ClientSession ruthSession = new ClientSession();
        registeredClients.put("Ruth", ruthSession);

        for (int i = 0; i < Constants.MAX_MAILBOX_SIZE; i++) {
            ruthSession.queueMessage(new Message(MessageType.DELIVERED, "dummy" + i, "James", "Ruth", "fill", null, null));
        }

        Message regMsg = new Message(MessageType.REGISTER, "1", "James", null, null, null, null);
        Message sendMsg = new Message(MessageType.SEND, "2", "James", "Ruth", "This should bounce", null, null);

        String input = objectMapper.writeValueAsString(regMsg) + "\n" +
                objectMapper.writeValueAsString(sendMsg) + "\n";

        String output = runHandlerWithInput(input);

        assertTrue(output.contains("\"type\":\"ERROR\""), "Handler should return an ERROR payload");
        assertTrue(output.contains("Recipient mailbox is full"), "Error reason should mention the full mailbox");
    }

    @Test
    @DisplayName("handleSend: Should deliver message and return RECEIPT when recipient is online")
    void testHandleSendOnlineRecipient() throws Exception {
        // register Ruth as an online client with an active mocked stream
        ClientSession ruthSession = new ClientSession();
        ruthSession.setConnection(mock(java.io.PrintWriter.class));
        registeredClients.put("Ruth", ruthSession);

        Message regMsg = new Message(MessageType.REGISTER, "1", "James", null, null, null, null);
        Message sendMsg = new Message(MessageType.SEND, "2", "James", "Ruth", "Hello Ruth", null, null);

        String input = objectMapper.writeValueAsString(regMsg) + "\n" +
                objectMapper.writeValueAsString(sendMsg) + "\n";

        String output = runHandlerWithInput(input);

        // verify the message was moved to Ruth's unacknowledged map (in-flight)
        assertTrue(ruthSession.getUnacknowledgedMessages().containsKey("2"), "Message should be in Ruth's unacknowledged map");
        assertNull(ruthSession.pollPendingMessage(), "Message should not be in the offline queue");

        // verify the sender received a RECEIPT confirmation
        assertTrue(output.contains("\"type\":\"RECEIPT\""), "Handler should return a RECEIPT payload");
        assertTrue(output.contains("delivered"), "Receipt reason should mention the message was delivered");
    }

    @Test
    @DisplayName("handleAck: Should remove message from unacknowledged map")
    void testHandleAck() throws Exception {
        // register Ruth and insert a pending unacknowledged message into the session
        ClientSession ruthSession = new ClientSession();
        Message unackedMsg = new Message(MessageType.DELIVERED, "msg-123", "James", "Ruth", "Hello Ruth", null, null);
        ruthSession.markUnacknowledged(unackedMsg);
        registeredClients.put("Ruth", ruthSession);

        // Ruth connects, registers, and sends the ACK for the message
        Message regMsg = new Message(MessageType.REGISTER, "1", "Ruth", null, null, null, null);
        Message ackMsg = new Message(MessageType.ACK, "msg-123", "Ruth", null, null, null, null);

        String input = objectMapper.writeValueAsString(regMsg) + "\n" +
                objectMapper.writeValueAsString(ackMsg) + "\n";

        runHandlerWithInput(input);

        // verify the message was successfully removed from Ruth's unacknowledged map
        assertTrue(ruthSession.getUnacknowledgedMessages().isEmpty(), "Unacknowledged map should be empty after ACK");
    }

    @Test
    @DisplayName("Unregistered Protection: Should reject ACK from unregistered socket")
    void testUnregisteredAck() throws Exception {
        Message ackMsg = new Message(MessageType.ACK, "msg-99", "James", null, null, null, null);

        // Run without sending a REGISTER message first
        runHandlerWithInput(objectMapper.writeValueAsString(ackMsg) + "\n");

        assertFalse(registeredClients.containsKey("James"), "James should not be implicitly registered by an ACK");
    }

    @Test
    @DisplayName("Error Handling: Should return ERROR for malformed JSON")
    void testMalformedJson() throws Exception {
        String input = "{ malformed json syntax }\n";
        String output = runHandlerWithInput(input);

        assertTrue(output.contains("\"type\":\"ERROR\""), "Handler should return an ERROR payload");
        assertTrue(output.contains("Invalid JSON format"), "Error payload should specify invalid format");
    }
}