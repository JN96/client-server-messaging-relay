package com.relay.state;

import com.relay.config.Constants;
import com.relay.protocol.Message;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Represents the state of a registered client.
 */
public class ClientSession {

    // tcp output stream, null if disconnected
    private PrintWriter activeConnection;

    // bounded (100) queue for message waiting to be sent
    private final ArrayBlockingQueue<Message> pendingQueue;


    // ordered and thread-safe map for messages sent but not acknowledged,
    // preserves chronological insertion order for FIFO
    // coded to interface - later initialised as synchronised LinkedHashMap
    // key value pair - <messageId, message>
    private final Map<String, Message> unacknowledgedMessages;

    public ClientSession() {
        this.pendingQueue = new ArrayBlockingQueue<>(Constants.MAX_MAILBOX_SIZE);
        this.unacknowledgedMessages = Collections.synchronizedMap(new LinkedHashMap<>());
    }

    /**
     * Attaches a tcp output stream to the session.
     * @param output - the output stream.
     */
    public synchronized void setConnection(final PrintWriter output) {
        this.activeConnection = output;
    }

    /**
     * Clears the connection without destroying session data, but only if {@code output} is still
     * the currently attached connection. Prevents a stale/disconnecting socket from clobbering a
     * newer connection that has already re-registered on the same client id.
     * @param output - the output stream the caller believes it owns.
     */
    public synchronized void clearConnection(final PrintWriter output) {
        if (this.activeConnection == output) {
            this.activeConnection = null;
        }
    }

    public synchronized PrintWriter getConnection() {
        return this.activeConnection;
    }

    public synchronized boolean isConnected() {
        return this.activeConnection != null;
    }

    /**
     * Attempt to add a message to the client's pending mailbox.
     * @param message true if added successfully or false if the mailbox is full.
     */
    public boolean queueMessage(final Message message) {
        return pendingQueue.offer(message);
    }

    /**
     * Retrieves and removes the next message from the pending queue.
     * @return the next message or null if the queue is empty.
     */
    public Message pollPendingMessage() {
        return this.pendingQueue.poll();
    }

    /**
     * Mark the that has been sent if it has not yet been acknowledged.
     * @param message the message currently in transit.
     */
    public void markUnacknowledged(final Message message) {
        this.unacknowledgedMessages.put(message.getMessageId(), message);
    }

    /**
     * Remove the message from the map once the recipient receives it.
     * @param messageId the id of the acknowledged message.
     */
    public void acknowledgeMessage(final String messageId) {
        this.unacknowledgedMessages.remove(messageId);
    }

    /**
     * Retrieves al unacknowledged messages.
     * @return a map of messages that require redelivery.
     */
    public Map<String, Message> getUnacknowledgedMessages() {
        return this.unacknowledgedMessages;
    }

}
