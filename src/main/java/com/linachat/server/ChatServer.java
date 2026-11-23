package com.linachat.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.linachat.model.Message;
import com.linachat.model.User;
import com.linachat.network.JsonCodec;

import java.io.*;
import java.net.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public final class ChatServer implements AutoCloseable {
    // virtual threads permettent d'ouvrir une session legere pour chaque client TCP.
    private final int port;
    //pur que chaque client soit servi dans un thread virtuel, on utilise un ExecutorService.
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    //stocker les connexions des utilisateurs actuellement connectés
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    // Stocke les informations des utilisateurs connus par le serveu
    private final ConcurrentHashMap<String, User> profiles = new ConcurrentHashMap<>();
    // Stocke les historiques de conversation entre utilisateurs
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Message>> histories = new ConcurrentHashMap<>();
    //si le serveur fonctionne
    private volatile boolean running;
    private ServerSocket socket;

    public ChatServer(int port) { this.port = port; }
    public int localPort() { return socket == null ? port : socket.getLocalPort(); }

    public void start() throws IOException {
        // accept() attend le prochain client; chaque connexion est ensuite isolee dans serve().
        socket = new ServerSocket(port);
        running = true;
        System.out.println("LinaChat Server écoute sur le port " + socket.getLocalPort());
        while (running) {
            try {
                Socket client = socket.accept();
                // le serveur soumet une tâche au gestionnaire clients
                clients.submit(() -> serve(client));
            } catch (SocketException e) {
                if (running) throw e;
            }
        }
    }

    private void serve(Socket connection) {
        // La boucle de lecture reste active pendant la session:
        // les exceptions ferment proprement la socket et retirent le profil en ligne.
        ClientSession session = new ClientSession(connection);
        try (connection) {
            session.out = connection.getOutputStream();
            while (running) {
                //Kol client 3ando boucle dyal lecture dyalo. Mlli kayseft chi données, serveur kay9rahom,
                //kay7awelhom l Map, w kay3ayet l handle() bach ychouf ach khaso ydir
                Map<String, Object> frame = JsonCodec.MAPPER.readValue(
                        JsonCodec.read(connection.getInputStream()), new TypeReference<>() {});
                handle(session, frame);
            }
        } catch (IOException ignored) {
        } finally {
            if (session.userId != null) {
                removeSession(session);
            }
        }
    }

    // déterminer ce que le client veut faire
    private void handle(ClientSession from, Map<String, Object> frame) throws IOException {
        // Le type de Frame decide si on met a jour un profil, demande l'historique
        // ou valide puis distribue un message texte, image ou fichier.
        String type = String.valueOf(frame.get("type"));
        if ("PROFILE".equals(type)) {
            //Récupère les informations de l'utilisateur et les convertit en objet User
            User user = JsonCodec.MAPPER.convertValue(frame.get("user"), User.class);
            if (user.name() == null || user.name().isBlank() || !validAvatar(user.avatarBase64())) {
                send(from, Map.of("type", "ERROR", "message", "Profil invalide."));
                return;
            }
            from.userId = user.id();
            //Enregistre le profil de l'utilisateur dans profiles (true indique que user est en ligne)
            profiles.put(user.id(), new User(user.id(), user.name(), user.status(), user.avatarBase64(), true));
            //Enregistre sa session dans la liste des clients connectés.
            sessions.put(user.id(), from);
            //Envoie au client une confirmation de son profil
            send(from, Map.of("type", "PROFILE", "user", profiles.get(user.id())));
            //informe tous les clients des utilisateurs disponibles et de leur statut
            broadcastContacts();
            return;
        }
        if ("DISCONNECT".equals(type)) {
            // Retire immédiatement cette session et son profil de la mémoire serveur.
            // Hna kanbandow synchronisé bach maybqach ghost user f list des contacts.
            removeSession(from);
            try { from.socket.close(); } catch (IOException ignored) { }
            return;
        }
        if ("HISTORY_REQUEST".equals(type)) {
            String contactId = String.valueOf(frame.get("contactId"));
            send(from, Map.of("type", "HISTORY", "messages", history(from.userId, contactId)));
            return;
        }
        if ("MESSAGE".equals(type) || "FILE".equals(type)
                || "IMAGE".equals(type) || "AUDIO".equals(type)) {
            //Transforme la Map reçue en objet Message
            Message message = JsonCodec.MAPPER.convertValue(frame, Message.class);
            //Vérifie que l'expéditeur est bien connecté, que le destinataire existe et que le fichier respecte la taille
            //autorisée (15 Mo maximum) ; vérifie aussi que l'image est valide.
            if (!message.sender().equals(from.userId) || !profiles.containsKey(message.recipient())
                    || message.fileSize() < 0 || message.fileSize() > 15L * 1024 * 1024
                    || ("IMAGE".equals(type) && !validImage(message))
                    || ("FILE".equals(type) && !validFile(message))
                    || ("AUDIO".equals(type) && !validAudio(message))) {
                //Si le message n'est pas valide, le serveur envoie une erreur à l'expéditeur
                send(from, Map.of("type", "ERROR", "message",
                        "IMAGE".equals(type) ? "Image invalide ou trop volumineuse."
                                : "AUDIO".equals(type) ? "Message vocal invalide ou trop volumineux."
                                : "Destinataire indisponible."));
                return;
            }
            //enregistre le message dans l'historique
            histories.computeIfAbsent(key(message.sender(), message.recipient()), ignored ->
                    new CopyOnWriteArrayList<>()).add(message);
            ClientSession recipient = sessions.get(message.recipient());
            //Si le destinataire est connecté, le serveur lui envoie le message
            if (recipient != null) send(recipient, frame);
        }
    }

    private boolean validImage(Message message) {
        // vérifie si le fichier envoyé est réellement une image valide
            try {
                // Si le message ne contient pas de données d'image ou de nom de fichier, il est invalide
                if (message.contentBase64() == null || message.fileName() == null) return false;
                // Décode le contenu de l'image à partir de la chaîne Base64 et vérifie sa taille et sa validité
                byte[] data = Base64.getDecoder().decode(message.contentBase64());
                return data.length == message.fileSize()
                        && javax.imageio.ImageIO.read(new ByteArrayInputStream(data)) != null;
            }
            //Si le décodage Base64 ou la lecture de l'image échoue, la méthode retourne false
            catch (IllegalArgumentException | IOException e) {
                return false;
            }
    }

    private boolean validFile(Message message) {
        // Vérifie les données réelles d'une pièce jointe avant l'historisation et le routage.
        // Hna kanshahado que name, taille et Base64 correspondou, bach le destinataire
        // ne recoive jamais une carte d'un fichier incomplet ou corrompu.
        try {
            if (message.contentBase64() == null || message.fileName() == null
                    || message.fileName().isBlank()) return false;
            String safeName = Paths.get(message.fileName()).getFileName().toString();
            if (safeName.isBlank() || safeName.contains("\n") || safeName.contains("\r")) return false;
            byte[] data = Base64.getDecoder().decode(message.contentBase64());
            return data.length == message.fileSize();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean validAudio(Message message) {
        // Hna kancheckiw le contenu reel, pas seulement le nom du fichier.
        try {
            if (message.contentBase64() == null || message.fileName() == null
                    || !message.fileName().toLowerCase(Locale.ROOT).endsWith(".wav")) {
                return false;
            }
            byte[] data = Base64.getDecoder().decode(message.contentBase64());
            return data.length == message.fileSize()
                    && data.length > 44
                    && data[0] == 'R' && data[1] == 'I'
                    && data[2] == 'F' && data[3] == 'F'
                    && data[8] == 'W' && data[9] == 'A'
                    && data[10] == 'V' && data[11] == 'E';
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    private boolean validAvatar(String base64) {
        // vérifie si l'avatar est une image valide et ne dépasse pas 5 Mo
            if (base64 == null || base64.isBlank()) return true;
            try {
                byte[] data = Base64.getDecoder().decode(base64);
                return data.length <= 5L * 1024 * 1024
                        && javax.imageio.ImageIO.read(new ByteArrayInputStream(data)) != null;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
    }
    private List<Message> history(String first, String second) {
        //récupère l'historique d'une conversation entre deux utilisateurs
        return List.copyOf(histories.getOrDefault(key(first, second), new CopyOnWriteArrayList<>()));
    }

    private String key(String first, String second) {
        // La cle est symetrique: A/B et B/A ont le meme historique.
        return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first;
    }

    private void removeSession(ClientSession session) {
        if (session.userId == null) return;
        // sessions.remove uniquement si cette socket correspond encore au profil.
        // Cela évite qu'une ancienne connexion supprimette une nouvelle reconnexion.
        sessions.computeIfPresent(session.userId, (id, active) ->
                active == session ? null : active);
        if (!sessions.containsKey(session.userId)
                || sessions.get(session.userId) == null) {
            profiles.remove(session.userId);
        }
        broadcastContacts();
    }

    private void broadcastContacts() {
        // Chaque changement de presence est envoye a toutes les sessions actives.
        Map<String, Object> frame = Map.of("type", "CONTACTS", "users", new ArrayList<>(profiles.values()));
        sessions.values().forEach(session -> {
            try { send(session, frame); } catch (IOException ignored) { }
        });
    }

    //centralise l'envoi des données vers un client
    private void send(ClientSession session, Object frame) throws IOException {
        synchronized (session) { JsonCodec.write(session.out, frame); }
    }

    //redéfinit close() de l'interface AutoCloseable
    @Override public void close() throws IOException {
        running = false;
        if (socket != null) socket.close();
        sessions.values().forEach(session -> {
            try { session.socket.close(); } catch (IOException ignored) { }
        });
        clients.shutdownNow();
    }

    private static final class ClientSession {
        final Socket socket; OutputStream out; String userId;
        ClientSession(Socket socket) { this.socket = socket; }
    }
}
