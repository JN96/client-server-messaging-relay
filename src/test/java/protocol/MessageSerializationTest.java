package protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.protocol.Message;
import com.relay.protocol.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should serialize object to JSON omitting null fields")
    void testSerializationOmitsNulls() throws Exception {
        Message message = new Message(MessageType.REGISTER, "1", "James", null, null, null, null);
        String json = objectMapper.writeValueAsString(message);

        assertTrue(json.contains("\"type\":\"REGISTER\""));
        assertTrue(json.contains("\"senderId\":\"James\""));
        assertFalse(json.contains("recipientId"), "Null fields should be ignored during serialization");
        assertFalse(json.contains("payload"), "Null fields should be ignored during serialization");
    }

    @Test
    @DisplayName("Should deserialize JSON payload into valid Message object")
    void testDeserialization() throws Exception {
        String json = "{\"type\":\"SEND\",\"messageId\":\"999\",\"senderId\":\"James\",\"recipientId\":\"Ruth\",\"payload\":\"Hi Ruth!\"}";

        Message message = objectMapper.readValue(json, Message.class);

        assertEquals(MessageType.SEND, message.getType());
        assertEquals("999", message.getMessageId());
        assertEquals("James", message.getSenderId());
        assertEquals("Ruth", message.getRecipientId());
        assertEquals("Hi Ruth!", message.getPayload());
        assertNull(message.getStatus());
    }

    @Test
    @DisplayName("Should serialize and deserialize all MessageType enum values")
    void testAllMessageTypes() throws Exception {
        for (MessageType type : MessageType.values()) {
            // create a message using the current type
            Message originalMessage = new Message(type, "id-" + type, "James", "Ruth", "Test payload", "OK", "None");

            // serialize to JSON string
            String json = objectMapper.writeValueAsString(originalMessage);

            // deserialize back to a Message object
            Message deserializedMessage = objectMapper.readValue(json, Message.class);

            // verify the type maps correctly in both directions
            assertEquals(type, deserializedMessage.getType(), "Failed for type: " + type);
            assertEquals("id-" + type, deserializedMessage.getMessageId());
        }
    }

}
