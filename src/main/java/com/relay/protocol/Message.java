package com.relay.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Model representation for the Message object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    private MessageType type;

    private String messageId;

    private String senderId;

    private String recipientId;

    private String payload;

    private String status;

    private String reason;

    public Message() {}

    public Message(final MessageType type, final String messageId, final String senderId, final String recipientId,
                   final String payload, final String status, final String reason) {
        this.type = type;
        this.messageId = messageId;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.payload = payload;
        this.status = status;
        this.reason = reason;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(final MessageType type) {
        this.type = type;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(final String messageId) {
        this.messageId = messageId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(final String senderId) {
        this.senderId = senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(final String recipientId) {
        this.recipientId = recipientId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(final String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(final String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", messageId='" + messageId + '\'' +
                ", senderId='" + senderId + '\'' +
                ", recipientId='" + recipientId + '\'' +
                ", payload='" + payload + '\'' +
                ", status='" + status + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}
