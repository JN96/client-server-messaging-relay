package com.relay.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.protocol.Message;
import com.relay.protocol.MessageType;
import com.relay.state.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the network input and output for a connected client; responsible for reading the
 * JSON from the TCP streams and mapping the data to the {@link Message} objects.
 */
public class ClientHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private static final int MAX_MESSAGE_LENGTH = 1024;

    private final Socket clientSocket;
    private final ConcurrentHashMap<String, ClientSession> registeredClients;
    private final ObjectMapper objectMapper;
    private String registeredClientId;

    public ClientHandler(final Socket clientSocket, final ConcurrentHashMap<String, ClientSession> registeredClients) {
        this.clientSocket = clientSocket;
        this.registeredClients = registeredClients;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(this.clientSocket.getInputStream())); // data stream from the TCP socket
            PrintWriter out = new PrintWriter(this.clientSocket.getOutputStream(), true); // data stream to send to the client, autoflush=true to remove lingering data from the buffer memory

            String inputLine;

            // read JSON from the TCP stream
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.trim().isEmpty()) { // ignore empty lines / accidental enters with netcat...
                    continue;
                }

                if (inputLine.length() > MAX_MESSAGE_LENGTH) {
                    logger.info("Message exceeded the size limit of {} and will not be included", MAX_MESSAGE_LENGTH);
                    continue;
                }

                Message message;
                try {
                    message = this.objectMapper.readValue(inputLine, Message.class);
                } catch (final JsonProcessingException e) {
                    logger.warn("Malformed JSON received: {}", inputLine);
                    sendJson(out, new Message(MessageType.ERROR, null, "SERVER", null, null, "FAILED", "Invalid JSON format"));
                    continue; // keep the connection loop open
                }

                if (message == null || message.getType() == null) {
                    sendJson(out, new Message(MessageType.ERROR, null, "SERVER", null, null, "FAILED", "Missing message type"));
                    continue;
                }

                logger.info("Successfully parsed message of type: {}", message.getType());

                switch (message.getType()) {
                    case REGISTER:
                        handleRegister(message, out);
                        break;
                    case SEND:
                        handleSend(message, out);
                        break;
                    case ACK:
                        handleAck(message);
                        break;
                    default:
                        handleUnsupported(message);
                }
            }
        } catch (final IOException exception) {
            logger.error("Error occurred while reading from socket: ", exception);
            throw new RuntimeException(exception);
        } finally {
            try {
                // Clean up session connection on disconnect
                if (this.registeredClientId != null) {
                    ClientSession session = this.registeredClients.get(this.registeredClientId);
                    if (session != null) {
                        session.clearConnection();
                        logger.info("Cleared connection for client {}", this.registeredClientId);
                    }
                }
                this.clientSocket.close(); // closes both input and output streams
            } catch (final IOException exception) {
                logger.error("Error occurred while closing socket: ", exception);
            }
        }
    }

    /**
     * Handles a REGISTER message by linking the connection to a client session thus creating one if one
     * does not exist while also flushing pending/unacknowledged messages.
     * @param message the REGISTER message.
     * @param out the outgoing network stream.
     */
    private void handleRegister(final Message message, final PrintWriter out) {
        String clientId = message.getSenderId();
        if (clientId == null || clientId.trim().isEmpty()) {
            return;
        }

        // create a persistent session
        ClientSession session = registeredClients.computeIfAbsent(clientId, id -> new ClientSession());

//        // single session only
//        synchronized (session) {
//            if (session.isConnected()) {
//                sendJson(out, new Message(
//                        MessageType.ERROR,
//                        message.getMessageId(),
//                        "SERVER",
//                        clientId,
//                        null,
//                        "FAILED",
//                        "Client ID already actively connected"
//                ));
//                logger.warn("Client ID {} already actively connected", clientId);
//                return;
//            }
//        }

        // prevent two client registering with the same id at the same time
        synchronized (session) {
            // attach the connection
            session.setConnection(out);
            this.registeredClientId = clientId;
            logger.info("Successfully registered client with id {}", clientId);
            flushOfflineMessages(session, out);
        }
    }

    /**
     * Handles a SEND message by looking up the recipient and attempts to deliver the message (online) or queues it (offline).
     * Replies with ERROR if the recipient does not exist or if the recipient's mailbox is full.
     * @param message the SEND message containing the payload.
     * @param out the outgoing network stream of the SENDER which is used for ERROR replies.
     */
    private void handleSend(final Message message, final PrintWriter out) {
        if (this.registeredClientId == null) { // prevent messages sent by unregistered clients
            sendJson(out, new Message(MessageType.ERROR, message.getMessageId(), "SERVER", null, null, "FAILED", "Must register first"));
            return;
        }

        String recipientId = message.getRecipientId();
        ClientSession recipientSession = this.registeredClients.get(recipientId);

        if (recipientSession  == null) {
            sendJson(out, new Message(MessageType.ERROR, message.getMessageId(), "SERVER", message.getSenderId(), null, "FAILED", "Unknown recipient"));
            return;
        }

        Message messageToDeliver = new Message(MessageType.DELIVERED, message.getMessageId(), message.getSenderId(), recipientId, message.getPayload(), null, null);

        PrintWriter recipientOut = recipientSession.getConnection();
        if (recipientSession.isConnected()) {
            // client is online therefore send it
            recipientSession.markUnacknowledged(messageToDeliver);
            sendJson(recipientOut, messageToDeliver);
        } else {
            // client is offline therefore queue it
            boolean queued = recipientSession.queueMessage(messageToDeliver);
            if (!queued) {
                sendJson(out, new Message(MessageType.ERROR, message.getMessageId(), "SERVER", message.getSenderId(), null, "FAILED", "Recipient mailbox is full "));
            }
        }
    }

    /**
     * Handles an ACK message by removing the given message from the sender's unacknowledged LinkedHashMap
     * resulting in no redelivery attempts.
     * @param message the ACK message.
     */
    private void handleAck(final Message message) {
        if (this.registeredClientId == null) {
            logger.warn("Received ACK from unregistered client socket");
            return;
        }

        ClientSession session = this.registeredClients.get(message.getSenderId());
        if (session != null) {
            session.acknowledgeMessage(message.getMessageId());
        }
    }

    private void handleUnsupported(final Message message) {
        // TODO - do something??
        logger.error("Unsupported message type: {}", message.getType());
    }

    /**
     * Handles the delivery of all pending message to a freshly connected client by attempting to
     * redeliver unacknowledged messages.
     * @param session the client's session containing the queues.
     * @param out the outgoing network stream for the client.
     */
    private void flushOfflineMessages(final ClientSession session, final PrintWriter out) {
        synchronized (session.getUnacknowledgedMessages()) { // synchronize on the underlying map to prevent ConcurrentModificationException when iterating
            session.getUnacknowledgedMessages().values().forEach(unacknowledgedMessage -> sendJson(out, unacknowledgedMessage));
        }
        Message pendingMessage;

        // deliver queued messages
        while ((pendingMessage = session.pollPendingMessage()) != null) {
            session.markUnacknowledged(pendingMessage);
            sendJson(out, pendingMessage);
        }
    }

    /**
     * Serilaises a {@link Message} to a JSON string for transmitting over a network.
     * @param out the outgoing network stream.
     * @param unacknowledgedMessage the message object to serialise.
     */
    private void sendJson(final PrintWriter out, final Message unacknowledgedMessage) {
        try {
            out.println(this.objectMapper.writeValueAsString(unacknowledgedMessage));
        } catch (JsonProcessingException exception) {
            logger.info("Error occurred while serilialising message: ", exception);
            throw new RuntimeException(exception);
        }
    }
}
