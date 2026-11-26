package com.linachat.android;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Client TCP natif : trames compatibles avec JsonCodec côté Desktop et serveur. */
final class ProtocolClient implements AutoCloseable {
    interface Listener {
        void onContacts(List<ChatUser> users);
        void onHistory(List<ChatMessage> messages);
        void onMessage(ChatMessage message);
        void onError(String error);
        void onAiResponse(String text);
        void onAiError(String error);
    }

    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<JSONObject> outgoing = new LinkedBlockingQueue<>();
    private final CountDownLatch profileAccepted = new CountDownLatch(1);
    private final AtomicReference<String> profileError = new AtomicReference<>();
    private volatile Socket socket;
    private volatile boolean closed;
    private volatile List<ChatUser> lastContacts;
    private Thread writerThread;
    private Thread readerThread;

    ProtocolClient(Listener listener) {
        this.listener = listener;
    }

    void setListener(Listener listener) {
        this.listener = listener;
        List<ChatUser> contacts = lastContacts;
        if (contacts != null) mainHandler.post(() -> listener.onContacts(contacts));
    }

    void connect(String host, int port, ChatUser profile) throws Exception {
        Socket connected = new Socket();
        connected.connect(new InetSocketAddress(host, port), 6000);
        connected.setTcpNoDelay(true);
        socket = connected;
        writerThread = new Thread(this::writeLoop, "linachat-writer");
        readerThread = new Thread(this::readLoop, "linachat-reader");
        writerThread.setDaemon(true);
        readerThread.setDaemon(true);
        writerThread.start();
        readerThread.start();

        JSONObject frame = new JSONObject();
        put(frame, "type", "PROFILE");
        put(frame, "user", profile.toJson());
        send(frame);

        if (!profileAccepted.await(6, TimeUnit.SECONDS)) {
            close();
            throw new IOException("Le serveur n'a pas confirmé le profil.");
        }
        String error = profileError.get();
        if (error != null) {
            close();
            throw new IOException(error);
        }
    }

    private void writeLoop() {
        try {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            while (!closed) {
                byte[] json = outgoing.take().toString().getBytes(StandardCharsets.UTF_8);
                output.writeInt(json.length);
                output.write(json);
                output.flush();
            }
        } catch (Exception error) {
            if (!closed) connectionFailed("Connexion interrompue pendant l'envoi.");
        }
    }

    private void readLoop() {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (!closed) {
                int length = input.readInt();
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    throw new IOException("Taille de trame serveur invalide.");
                }
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                handle(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
            }
        } catch (EOFException error) {
            if (!closed) connectionFailed("Le serveur a fermé la connexion.");
        } catch (Exception error) {
            if (!closed) connectionFailed("Connexion interrompue ou trame serveur invalide.");
        }
    }

    private void handle(JSONObject frame) throws Exception {
        String type = frame.optString("type");
        switch (type) {
            case "PROFILE":
                profileAccepted.countDown();
                break;
            case "ERROR":
                String error = frame.optString("message", "Erreur serveur.");
                if (profileAccepted.getCount() > 0) profileError.set(error);
                profileAccepted.countDown();
                reportError(error);
                break;
            case "CONTACTS":
                JSONArray rawUsers = frame.optJSONArray("users");
                List<ChatUser> users = new ArrayList<>();
                if (rawUsers != null) {
                    for (int i = 0; i < rawUsers.length(); i++) {
                        JSONObject user = rawUsers.optJSONObject(i);
                        if (user != null) users.add(ChatUser.fromJson(user));
                    }
                }
                lastContacts = users;
                mainHandler.post(() -> listener.onContacts(users));
                break;
            case "HISTORY":
                JSONArray rawMessages = frame.optJSONArray("messages");
                List<ChatMessage> messages = new ArrayList<>();
                if (rawMessages != null) {
                    for (int i = 0; i < rawMessages.length(); i++) {
                        JSONObject message = rawMessages.optJSONObject(i);
                        if (message != null) messages.add(ChatMessage.fromJson(message));
                    }
                }
                mainHandler.post(() -> listener.onHistory(messages));
                break;
            case "MESSAGE":
            case "FILE":
            case "IMAGE":
            case "AUDIO":
                ChatMessage message = ChatMessage.fromJson(frame);
                mainHandler.post(() -> listener.onMessage(message));
                break;
            case "AI_RESPONSE":
                String response = frame.optString("text");
                mainHandler.post(() -> listener.onAiResponse(response));
                break;
            case "AI_ERROR":
                String aiError = frame.optString("message", "Réponse IA indisponible.");
                mainHandler.post(() -> listener.onAiError(aiError));
                break;
            default:
                reportError("Type de message serveur non reconnu : " + type);
        }
    }

    void selectConversation(String contactId) {
        JSONObject frame = new JSONObject();
        put(frame, "type", "HISTORY_REQUEST");
        put(frame, "contactId", contactId);
        send(frame);
    }

    void sendText(String recipientId, String text) {
        send(ChatMessage.text(Session.user.id, recipientId, text).toJson());
    }

    void sendAttachment(ChatMessage message) {
        send(message.toJson());
    }

    void sendAiRequest(String text) {
        JSONObject frame = new JSONObject();
        put(frame, "type", "AI_REQUEST");
        put(frame, "text", text);
        send(frame);
    }

    private void send(JSONObject frame) {
        if (closed) {
            reportError("La connexion au serveur est fermée.");
            return;
        }
        outgoing.offer(frame);
    }

    private void reportError(String error) {
        Listener current = listener;
        if (current != null) mainHandler.post(() -> current.onError(error));
    }

    private void connectionFailed(String error) {
        if (profileAccepted.getCount() > 0) profileError.set(error);
        profileAccepted.countDown();
        reportError(error);
        close();
    }

    void disconnect() {
        if (closed) return;
        JSONObject frame = new JSONObject();
        put(frame, "type", "DISCONNECT");
        outgoing.offer(frame);
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            close();
        }, "linachat-disconnect");
        closer.setDaemon(true);
        closer.start();
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (writerThread != null) writerThread.interrupt();
        if (readerThread != null) readerThread.interrupt();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // La fermeture libère aussi les boucles réseau en attente.
            }
        }
    }
}
