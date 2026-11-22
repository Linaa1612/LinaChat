package com.linachat.controller;

import com.linachat.client.ChatClient;
import com.linachat.model.Message;
import com.linachat.model.User;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.ByteArrayInputStream;

/**
 * Controleur JavaFX de l'ecran principal.
 * Hna kanconnectiw les elements FXML aux listes de conversations, aux images
 * et aux envois TCP geres par ChatClient.
 */
public final class ChatController {
    // Les listes qui suivent separent l'etat graphique de l'etat reseau:
    // chaque conversation garde son historique, son dernier message et ses non lus.
    @FXML private ListView<User> usersList;
    @FXML private VBox messagesBox;
    @FXML private TextField messageField;
    @FXML private Label contactLabel, statusLabel, profileNameLabel, profileStatusLabel;
    @FXML private ImageView profileAvatar, contactAvatar;
    @FXML private ScrollPane messageScroll;

    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();
    private final Map<String, Message> lastMessages = new ConcurrentHashMap<>();
    private final Map<String, Integer> unread = new ConcurrentHashMap<>();
    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
    private ChatClient client;
    private volatile String recipient;

    // Cette methode est appelee apres le chargement du FXML. Elle relie le client TCP
    // aux callbacks JavaFX et initialise l'affichage du profil et des contacts.
    public void initializeClient(ChatClient chatClient) {
        client = chatClient;
        User me = client.user();
        profileNameLabel.setText(me.name());
        profileStatusLabel.setText(me.status().isBlank() ? "Disponible" : me.status());
        setAvatar(profileAvatar, me.avatarBase64(), 36);
        usersList.setCellFactory(view -> new ListCell<>() {
            private final ImageView avatar = new ImageView();
            @Override protected void updateItem(User user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                } else {
                    Message last = lastMessages.get(user.id());
                    String preview = last == null ? "" : "  |  " + (last.text() == null ? last.fileName() : last.text());
                    int pending = unread.getOrDefault(user.id(), 0);
                    setText(user.name() + (user.online() ? "  • En ligne" : "  • Hors ligne")
                            + preview + (pending == 0 ? "" : "  (" + pending + ")"));
                }
                if (empty || user == null) setGraphic(null);
                else { setAvatar(avatar, user.avatarBase64(), 30); setGraphic(avatar); }
            }
        });
        usersList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> select(selected));
        client.onContacts(users -> Platform.runLater(() -> {
            String selectedId = recipient;
            usersList.getItems().setAll(users.stream().filter(user -> !user.id().equals(me.id())).toList());
            if (selectedId != null) usersList.getItems().stream().filter(user -> user.id().equals(selectedId)).findFirst()
                    .ifPresent(user -> usersList.getSelectionModel().select(user));
        }));
        client.onHistory(history -> Platform.runLater(() -> {
            if (recipient != null) {
                conversations.put(recipient, new ArrayList<>(history));
                if (!history.isEmpty()) lastMessages.put(recipient, history.get(history.size() - 1));
                usersList.refresh();
                renderConversation();
            }
        }));
        client.onMessage(message -> Platform.runLater(() -> {
            String partner = conversationPartner(message);
            conversations.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(message);
            lastMessages.put(partner, message);
            if (!Objects.equals(partner, recipient)) unread.merge(partner, 1, Integer::sum);
            usersList.refresh();
            if (message.belongsTo(client.user().id(), recipient == null ? "" : recipient)) renderMessage(message);
        }));
        client.onError(error -> Platform.runLater(() -> statusLabel.setText(error)));
    }

    private void select(User user) {
        if (user == null) return;
        recipient = user.id();
        unread.remove(recipient);
        contactLabel.setText(user.name());
        statusLabel.setText(user.online() ? "En ligne" : "Hors ligne");
        setAvatar(contactAvatar, user.avatarBase64(), 34);
        conversations.computeIfAbsent(recipient, ignored -> new ArrayList<>());
        messagesBox.getChildren().clear();
        client.selectConversation(recipient);
        renderConversation();
    }

    // @FXML relie cette methode a onAction="#send" dans le FXML.
    // Le message est affiche immediatement, puis envoye au serveur via le socket TCP.
    @FXML private void send() {
        String text = messageField.getText().trim();
        if (recipient == null || text.isBlank()) return;
        Message message = Message.text(client.user().id(), recipient, text);
        conversations.computeIfAbsent(recipient, ignored -> new ArrayList<>()).add(message);
        lastMessages.put(recipient, message);
        usersList.refresh();
        renderMessage(message);
        messageField.clear();
        client.sendText(recipient, text);
    }

    // Hna kanchoisiw fichier: les images ont une previsualisation,
    // tandis que les autres fichiers sont transferes comme pieces jointes.
    @FXML private void attach() {
        if (recipient == null) return;
        File file = new FileChooser().showOpenDialog(messageField.getScene().getWindow());
        if (file == null) return;
        if (isImage(file)) {
            Image preview = new Image(file.toURI().toString(), 260, 180, true, true);
            ImageView previewView = new ImageView(preview);
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, "Envoyer cette image ?", ButtonType.YES, ButtonType.NO);
            confirmation.setHeaderText(file.getName());
            confirmation.getDialogPane().setContent(previewView);
            if (confirmation.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
            client.sendImage(recipient, file).thenAccept(message -> Platform.runLater(() -> {
                conversations.computeIfAbsent(recipient, ignored -> new ArrayList<>()).add(message);
                lastMessages.put(recipient, message);
                usersList.refresh();
                renderMessage(message);
            })).exceptionally(error -> { Platform.runLater(() -> statusLabel.setText(imageError(error))); return null; });
        } else {
            try { client.sendFile(recipient, file); statusLabel.setText("Fichier envoyé."); }
            catch (Exception e) { statusLabel.setText("Impossible d'envoyer le fichier."); }
        }
    }

    private void renderConversation() {
        messagesBox.getChildren().clear();
        conversations.getOrDefault(recipient, List.of()).forEach(this::renderMessage);
    }

    private void renderMessage(Message message) {
        javafx.scene.Node bubble;
        if ("IMAGE".equals(message.type())) {
            bubble = imageBubble(message);
        } else {
            String content = message.text() != null ? message.text() : "Fichier : " + message.fileName();
            Label label = new Label(content + "\n" + time.format(message.timestamp().atZone(ZoneId.systemDefault())));
            label.setWrapText(true);
            bubble = label;
        }
        bubble.getStyleClass().add(message.sender().equals(client.user().id()) ? "message-out" : "message-in");
        messagesBox.getChildren().add(bubble);
        messageScroll.setVvalue(1.0);
    }

    private javafx.scene.Node imageBubble(Message message) {
        try {
            byte[] bytes = Base64.getDecoder().decode(message.contentBase64());
            Path folder = Paths.get("storage", "images");
            Files.createDirectories(folder);
            Path imagePath = folder.resolve(message.id() + "-" + Paths.get(message.fileName()).getFileName());
            Files.write(imagePath, bytes);
            ImageView image = new ImageView(new Image(new ByteArrayInputStream(bytes), 320, 240, true, true));
            image.setPreserveRatio(true);
            image.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    try { new ProcessBuilder("cmd", "/c", "start", "", imagePath.toAbsolutePath().toString()).start(); }
                    catch (Exception ignored) { statusLabel.setText("Impossible d'ouvrir l'image."); }
                }
            });
            return image;
        } catch (Exception e) {
            return new Label("Image indisponible.");
        }
    }

    private boolean isImage(File file) {
        try { return file.isFile() && javax.imageio.ImageIO.read(file) != null; }
        catch (Exception e) { return false; }
    }

    private String imageError(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? "Impossible d'envoyer l'image." : cause.getMessage();
    }

    private String conversationPartner(Message message) {
        return message.sender().equals(client.user().id()) ? message.recipient() : message.sender();
    }

    private void setAvatar(ImageView view, String base64, double size) {
        view.setFitWidth(size); view.setFitHeight(size); view.setPreserveRatio(true);
        if (base64 == null || base64.isBlank()) { view.setImage(null); return; }
        try { view.setImage(new Image(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64)))); }
        catch (IllegalArgumentException ignored) { view.setImage(null); }
    }
    // Les actions JavaFX doivent rester sur le thread graphique. Ici, le bouton ouvre
    // seulement les parametres locaux du profil et informe l'utilisateur sans blocage.
    @FXML private void openSettings() { statusLabel.setText("Paramètres du profil disponibles prochainement."); }
}
