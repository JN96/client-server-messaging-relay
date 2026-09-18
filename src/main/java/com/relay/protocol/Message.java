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

    public Message(MessageType type, String messageId, String senderId, String recipientId, String payload, String status, String reason) {
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

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
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
