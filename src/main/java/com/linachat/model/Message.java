package com.linachat.model;

import java.time.Instant;
import java.util.UUID;

public record Message(String id, String type, String sender, String recipient, String text,
                      String fileName, long fileSize, String contentBase64, Instant timestamp) {
    // Les fabriques centralisent la creation des trois types de message pour eviter
    // des champs incoherents entre le client, le serveur et la vue JavaFX.
    public static Message text(String from, String to, String body) {
        return new Message(UUID.randomUUID().toString(), "MESSAGE", from, to, body, null, 0, null, Instant.now());
    }
    public static Message file(String from, String to, String name, byte[] bytes) {
        return new Message(UUID.randomUUID().toString(), "FILE", from, to, null, name, bytes.length,
                java.util.Base64.getEncoder().encodeToString(bytes), Instant.now());
    }

    public static Message image(String from, String to, String name, byte[] bytes) {
        return new Message(UUID.randomUUID().toString(), "IMAGE", from, to, null, name, bytes.length,
                java.util.Base64.getEncoder().encodeToString(bytes), Instant.now());
    }

    public boolean belongsTo(String first, String second) {
        return (sender.equals(first) && recipient.equals(second))
                || (sender.equals(second) && recipient.equals(first));
    }
}
