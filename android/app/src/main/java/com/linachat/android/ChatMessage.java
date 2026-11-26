package com.linachat.android;

import org.json.JSONObject;

import java.time.Instant;
import java.util.UUID;

final class ChatMessage {
    final String id;
    final String type;
    final String sender;
    final String recipient;
    final String text;
    final String fileName;
    final long fileSize;
    final String contentBase64;
    final String timestamp;
    final long durationMillis;

    ChatMessage(String id, String type, String sender, String recipient, String text,
                String fileName, long fileSize, String contentBase64,
                String timestamp, long durationMillis) {
        this.id = id;
        this.type = type;
        this.sender = sender;
        this.recipient = recipient;
        this.text = text;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentBase64 = contentBase64;
        this.timestamp = timestamp;
        this.durationMillis = durationMillis;
    }

    static ChatMessage text(String sender, String recipient, String text) {
        return new ChatMessage(UUID.randomUUID().toString(), "MESSAGE", sender, recipient,
                text, null, 0, null, Instant.now().toString(), 0);
    }

    static ChatMessage attachment(String type, String sender, String recipient,
                                  String fileName, byte[] bytes, long durationMillis) {
        return new ChatMessage(UUID.randomUUID().toString(), type, sender, recipient,
                null, fileName, bytes.length,
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                Instant.now().toString(), durationMillis);
    }

    JSONObject toJson() {
        JSONObject message = new JSONObject();
        put(message, "id", id);
        put(message, "type", type);
        put(message, "sender", sender);
        put(message, "recipient", recipient);
        put(message, "text", text == null ? JSONObject.NULL : text);
        put(message, "fileName", fileName == null ? JSONObject.NULL : fileName);
        put(message, "fileSize", fileSize);
        put(message, "contentBase64", contentBase64 == null ? JSONObject.NULL : contentBase64);
        put(message, "timestamp", timestamp);
        put(message, "durationMillis", durationMillis);
        return message;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    static ChatMessage fromJson(JSONObject message) {
        Object textValue = message.opt("text");
        Object fileNameValue = message.opt("fileName");
        Object contentValue = message.opt("contentBase64");
        return new ChatMessage(message.optString("id"), message.optString("type"),
                message.optString("sender"), message.optString("recipient"),
                textValue instanceof String ? (String) textValue : null,
                fileNameValue instanceof String ? (String) fileNameValue : null,
                message.optLong("fileSize"),
                contentValue instanceof String ? (String) contentValue : null,
                message.optString("timestamp", Instant.now().toString()),
                message.optLong("durationMillis"));
    }

    String conversationPartner(String userId) {
        return sender.equals(userId) ? recipient : sender;
    }
}
