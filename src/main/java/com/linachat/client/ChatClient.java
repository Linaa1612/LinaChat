package com.linachat.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.linachat.model.Message;
import com.linachat.model.User;
import com.linachat.network.JsonCodec;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

public final class ChatClient implements AutoCloseable {
    // Trois pools gerent des responsabilites distinctes:
    // writer envoie les trames, reader les recoit, transfers prepare les images.
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final ExecutorService transfers = Executors.newFixedThreadPool(2);
    private static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;
    private final BlockingQueue<Object> outgoing = new LinkedBlockingQueue<>();
    private volatile Socket socket;
    private volatile User currentUser;
    private Consumer<Message> messageHandler = m -> {};
    private Consumer<List<User>> contactsHandler = c -> {};
    private Consumer<List<Message>> historyHandler = h -> {};
    private Consumer<String> errorHandler = e -> {};

    public CompletableFuture<User> connect(String host, int port, User profile) {
        // supplyAsync evite de bloquer le thread JavaFX pendant l'ouverture du socket.
        return CompletableFuture.supplyAsync(() -> {
            try {
                socket = new Socket(host, port);
                currentUser = profile;
                writer.submit(this::writeLoop);
                reader.submit(this::readLoop);
                outgoing.put(Map.of("type", "PROFILE", "user", profile));
                return profile;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private void writeLoop() {
        // Une seule thread lit la queue, ce qui preserve l'ordre d'envoi des messages.
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JsonCodec.write(socket.getOutputStream(), outgoing.take());
            }
        } catch (Exception e) {
            if (socket != null && !socket.isClosed()) errorHandler.accept("Connexion interrompue.");
        }
    }

    private void readLoop() {
        // Hna kanqraw trames JSON depuis le ServerSocket:
        // contacts, historique, messages texte, fichiers et images sont routes selon leur type.
        try {
            while (!socket.isClosed()) {
                Map<String, Object> frame = JsonCodec.MAPPER.readValue(
                        JsonCodec.read(socket.getInputStream()), new TypeReference<>() {});
                String type = String.valueOf(frame.get("type"));
                if ("CONTACTS".equals(type)) {
                    contactsHandler.accept(JsonCodec.MAPPER.convertValue(frame.get("users"), new TypeReference<>() {}));
                } else if ("MESSAGE".equals(type) || "FILE".equals(type) || "IMAGE".equals(type)) {
                    messageHandler.accept(JsonCodec.MAPPER.convertValue(frame, Message.class));
                } else if ("HISTORY".equals(type)) {
                    historyHandler.accept(JsonCodec.MAPPER.convertValue(frame.get("messages"), new TypeReference<>() {}));
                } else if ("ERROR".equals(type)) {
                    errorHandler.accept(String.valueOf(frame.get("message")));
                }
            }
        } catch (IOException e) {
            if (socket != null && !socket.isClosed()) errorHandler.accept("Connexion interrompue.");
        }
    }

    public void selectConversation(String contactId) {
        outgoing.add(Map.of("type", "HISTORY_REQUEST", "contactId", contactId));
    }

    public void sendText(String recipientId, String text) {
        outgoing.add(JsonCodec.MAPPER.convertValue(
                Message.text(currentUser.id(), recipientId, text), new TypeReference<Map<String, Object>>() {}));
    }

    public void sendFile(String recipientId, File file) throws IOException {
        outgoing.add(JsonCodec.MAPPER.convertValue(
                Message.file(currentUser.id(), recipientId, file.getName(), java.nio.file.Files.readAllBytes(file.toPath())),
                new TypeReference<Map<String, Object>>() {}));
    }

    public CompletableFuture<Message> sendImage(String recipientId, File file) {
        // La validation locale limite la taille et le format avant l'encodage Base64.
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!file.isFile()) throw new IOException("Image introuvable.");
                if (file.length() > MAX_IMAGE_BYTES) throw new IOException("Image trop volumineuse (15 Mo maximum).");
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new IOException("Format d'image non pris en charge.");
                Message image = Message.image(currentUser.id(), recipientId, file.getName(), bytes);
                outgoing.put(JsonCodec.MAPPER.convertValue(image, new TypeReference<Map<String, Object>>() {}));
                return image;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, transfers);
    }

    public User user() { return currentUser; }
    public void onMessage(Consumer<Message> handler) { messageHandler = handler; }
    public void onContacts(Consumer<List<User>> handler) { contactsHandler = handler; }
    public void onHistory(Consumer<List<Message>> handler) { historyHandler = handler; }
    public void onError(Consumer<String> handler) { errorHandler = handler; }

    @Override public void close() throws IOException {
        if (socket != null) socket.close();
        writer.shutdownNow();
        reader.shutdownNow();
        transfers.shutdownNow();
    }
}
