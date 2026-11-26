package com.linachat.controller;

import com.linachat.client.ChatClient;
import com.linachat.model.Message;
import com.linachat.model.User;
import com.linachat.service.AudioRecorder;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import java.awt.Desktop;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

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
    @FXML private HBox composerBox, recordingBar, previewBar;
    @FXML private Label recordingDurationLabel, previewDurationLabel;
    @FXML private Slider previewSlider;
    @FXML private Button previewPlayButton;
    @FXML private BorderPane chatPane, aiPane;
    @FXML private VBox aiMessagesBox;
    @FXML private TextField aiMessageField;
    @FXML private Button aiSendButton;
    @FXML private ProgressIndicator aiLoading;
    @FXML private ScrollPane aiScroll;

    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();
    private final Map<String, Message> lastMessages = new ConcurrentHashMap<>();
    private final Map<String, Integer> unread = new ConcurrentHashMap<>();
    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
    private ChatClient client;
    private volatile String recipient;
    private final AudioRecorder audioRecorder = new AudioRecorder();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, MediaPlayer> audioPlayers = new ConcurrentHashMap<>();
    private final Timeline recordingTimeline = new Timeline();
    private MediaPlayer previewPlayer;
    private File previewAudioFile;
    private long previewDurationMillis;
    private long recordingStartedNanos;
    private Popup emojiPopup;
    private Path temporaryAttachmentDirectory;

    // Cette methode est appelee apres le chargement du FXML. Elle relie le client TCP
    // aux callbacks JavaFX et initialise l'affichage du profil et des contacts.
    public void initializeClient(ChatClient chatClient) {
        client = chatClient;
        buildEmojiPicker();
        try {
            temporaryAttachmentDirectory = Files.createDirectories(
                    Paths.get(System.getProperty("java.io.tmpdir"), "LinaChat", "attachments"));
        } catch (IOException e) {
            statusLabel.setText("Impossible de préparer le dossier temporaire des pièces jointes.");
        }
        User me = client.user();
        profileNameLabel.setText(me.name());
        profileStatusLabel.setText(me.status().isBlank() ? "Disponible" : me.status());
        setAvatar(profileAvatar, me.avatarBase64(), 36);
        usersList.setCellFactory(view -> new ListCell<>() {
            private final ImageView avatar = new ImageView();
            @Override
            protected void updateItem(User user, boolean empty) {
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
        client.onAiResponse(answer -> Platform.runLater(() -> {
            aiLoading.setVisible(false);
            aiLoading.setManaged(false);
            aiSendButton.setDisable(false);
            aiMessageField.setDisable(false);
            addAiBubble(answer, "ai-assistant");
            statusLabel.setText("Réponse IA reçue.");
        }));
        client.onAiError(error -> Platform.runLater(() -> {
            aiLoading.setVisible(false);
            aiLoading.setManaged(false);
            aiSendButton.setDisable(false);
            aiMessageField.setDisable(false);
            Label bubble = new Label(error);
            bubble.getStyleClass().addAll("ai-assistant", "ai-error");
            aiMessagesBox.getChildren().add(bubble);
            statusLabel.setText(error);
            scrollAiToEnd();
        }));

        // Chronometre JavaFX: il ne touche pas au micro ni au fichier.
        recordingTimeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(200), event -> {
            long elapsed = (System.nanoTime() - recordingStartedNanos) / 1_000_000;
            recordingDurationLabel.setText(formatDurationMillis(elapsed));
        }));
        recordingTimeline.setCycleCount(Timeline.INDEFINITE);
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

    /**
     * Ouvre ou ferme le panneau emoji sous la barre de composition.
     * Hna Popup kaybqa te7t champ, kajunction search/categories/sucessive selection.
     */
    @FXML private void toggleEmojiPicker() {
        if (emojiPopup == null) buildEmojiPicker();
        if (emojiPopup.isShowing()) {
            emojiPopup.hide();
        } else {
            emojiPopup.show(composerBox.getScene().getWindow(),
                    composerBox.localToScreen(composerBox.getLayoutBounds()).getMinX(),
                    composerBox.localToScreen(composerBox.getLayoutBounds()).getMaxY());
        }
    }

    private void buildEmojiPicker() {
        emojiPopup = new Popup();
        emojiPopup.setAutoHide(true);
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        panel.getStyleClass().add("emoji-panel");

        TextField search = new TextField();
        search.setPromptText("Rechercher un emoji...");
        search.getStyleClass().add("search");
        FlowPane searchResults = new FlowPane(6, 6);
        searchResults.setPrefWidth(420);
        searchResults.setPrefWrapLength(420);

        TabPane tabs = new TabPane();
        tabs.setPrefWidth(440);
        tabs.setPrefHeight(280);
        Map<String, String> categories = emojiCategories();
        for (Map.Entry<String, String> category : categories.entrySet()) {
            GridPane grid = new GridPane();
            grid.setHgap(6);
            grid.setVgap(6);
            grid.setPadding(new Insets(8));
            String[] emojis = category.getValue().split(" ");
            for (int i = 0; i < emojis.length; i++) {
                Button emojiButton = emojiButton(emojis[i]);
                grid.add(emojiButton, i % 12, i / 12);
            }
            ScrollPane scroll = new ScrollPane(grid);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("emoji-scroll");
            Tab tab = new Tab(category.getKey(), scroll);
            tab.setClosable(false);
            tabs.getTabs().add(tab);
        }

        // La recherche filtre tous les emojis disponibles sans modifier le texte du champ.
        search.textProperty().addListener((obs, oldValue, value) -> {
            searchResults.getChildren().clear();
            if (value == null || value.isBlank()) return;
            String query = value.trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> category : categories.entrySet()) {
                for (String emoji : category.getValue().split(" ")) {
                    if (emojiNames().getOrDefault(emoji, "").contains(query)
                            || category.getKey().toLowerCase(Locale.ROOT).contains(query)) {
                        searchResults.getChildren().add(emojiButton(emoji));
                    }
                }
            }
        });
        searchResults.setVisible(false);
        searchResults.setManaged(false);
        search.textProperty().addListener((obs, oldValue, value) -> {
            boolean searching = value != null && !value.isBlank();
            tabs.setVisible(!searching);
            tabs.setManaged(!searching);
            searchResults.setVisible(searching);
            searchResults.setManaged(searching);
        });

        Button close = new Button("Fermer");
        close.getStyleClass().add("secondary-button");
        close.setOnAction(event -> emojiPopup.hide());
        HBox header = new HBox(8, search, close);
        panel.getChildren().addAll(header, tabs, searchResults);
        emojiPopup.getContent().add(panel);
        emojiPopup.setOnHidden(event -> {
            search.clear();
        });
    }

    private Button emojiButton(String emoji) {
        Button button = new Button(emoji);
        button.getStyleClass().add("emoji-button");
        button.setOnAction(event -> {
            int position = messageField.getCaretPosition();
            messageField.insertText(position, emoji);
            messageField.requestFocus();
            // Le popup reste ouvert pour permettre plusieurs emojis successifs.
        });
        return button;
    }

    private Map<String, String> emojiCategories() {
        LinkedHashMap<String, String> categories = new LinkedHashMap<>();
        categories.put("Smileys", "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😔 😟 🙁 ☹️ 😢 😂");
        categories.put("Gestes", "👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🧠");
        categories.put("Nature", "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🦋 🌲 🌳 🌴 🌵 🌱 🌿 ☘️ 🍀 🌸 🌻");
        categories.put("Nourriture", "🍇 🍈 🍉 🍊 🍋 🍌 🍍 🥭 🍎 🍏 🍐 🍑 🍒 🍓 🥝 🍅 🥑 🍆 🥕 🌽 🥔 🍞 🥐 🥖 🧀 🍔 🍟 🍕 🌮 🌯 🥗 🍝 🍣 🍰 🎂 ☕ 🧋");
        categories.put("Voyages", "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍️ 🚲 ✈️ 🚀 🛸 🚢 ⛵ 🚉 🚇 🚊 🗺️ 🧭 🏔️ ⛰️ 🌋 🏖️ 🏰");
        categories.put("Activités", "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🥊 🥋 🎿 ⛷️ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏇 🧘 🎤 🎧 🎼 🎹 🥁 🎸");
        categories.put("Objets", "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ 🧰 🔧 🔨 🔐 🔑 🧲 💡 🔦 🏷️");
        categories.put("Symboles", "❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💔 ❣️ 💯 💢 💥 💫 💦 💨 🕳️ 💬 👁️‍🗨️ 💭 ♠️ ♥️ ♦️ ♣️ 🅰️ 🅱️ ✅ ❌ ⭕ 💠");
        categories.put("Drapeaux", "🇲🇦 🇫🇷 🇺🇸 🇬🇧 🇪🇸 🇮🇹 🇩🇪 🇨🇦 🇧🇷 🇯🇵 🇹🇷 🇸🇦 🇪🇬 🇩🇿 🇹🇳 🇨🇳 🇰🇷 🇦🇺 🇮🇳");
        return categories;
    }

    private Map<String, String> emojiNames() {
        // Les noms sont volontairement simples pour rendre la recherche rapide.
        Map<String, String> names = new HashMap<>();
        names.put("😀", "sourire heureux"); names.put("😂", "rire larmes"); names.put("😊", "sourire");
        names.put("😍", "coeur amour yeux"); names.put("😢", "pleurer triste"); names.put("👍", "pouce ok");
        names.put("👎", "pouce non"); names.put("❤️", "coeur amour"); names.put("☕", "cafe");
        names.put("🎵", "musique"); names.put("🎤", "micro"); names.put("⚽", "football sport");
        names.put("🇲🇦", "maroc marocain"); names.put("🇫🇷", "france");
        return names;
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

    // Bascule entre la discussion privée classique et l'assistant IA.
    // Aucune trame utilisateur privée n'est envoyée dans les requêtes AI_REQUEST.
    @FXML private void openAiAssistant() {
        chatPane.setVisible(false);
        chatPane.setManaged(false);
        aiPane.setVisible(true);
        aiPane.setManaged(true);
        aiMessageField.requestFocus();
    }

    @FXML private void closeAiAssistant() {
        aiPane.setVisible(false);
        aiPane.setManaged(false);
        chatPane.setVisible(true);
        chatPane.setManaged(true);
    }

    /** Envoi asynchrone côté serveur: JavaFX reste réactif pendant la génération. */
    @FXML private void sendAi() {
        String text = aiMessageField.getText().trim();
        if (text.isBlank() || aiSendButton.isDisable()) return;
        addAiBubble(text, "ai-user");
        aiMessageField.clear();
        aiSendButton.setDisable(true);
        aiMessageField.setDisable(true);
        aiLoading.setVisible(true);
        aiLoading.setManaged(true);
        statusLabel.setText("L'assistant IA réfléchit...");
        client.sendAiRequest(text);
    }

    private void addAiBubble(String text, String style) {
        Label bubble = new Label(text);
        bubble.getStyleClass().add(style);
        bubble.setMaxWidth(520);
        aiMessagesBox.getChildren().add(bubble);
        scrollAiToEnd();
    }

    private void scrollAiToEnd() {
        Platform.runLater(() -> aiScroll.setVvalue(1.0));
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
            client.sendFileAsync(recipient, file).thenAccept(message -> Platform.runLater(() -> {
                conversations.computeIfAbsent(recipient, ignored -> new ArrayList<>()).add(message);
                lastMessages.put(recipient, message);
                usersList.refresh();
                renderMessage(message);
                statusLabel.setText("Fichier envoyé.");
            })).exceptionally(error -> {
                Platform.runLater(() -> {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    statusLabel.setText(cause.getMessage() == null
                            ? "Impossible d'envoyer le fichier." : cause.getMessage());
                });
                return null;
            });
        }
    }

    // Demarre la capture du microphone; l'ecriture reste hors du thread JavaFX.
    // Hna kanbda enregistrer le sawt dyal l'utilisateur comme WhatsApp.
    @FXML private void startRecording() {
        if (recipient == null) {
            statusLabel.setText("Sélectionnez un contact avant d'enregistrer.");
            return;
        }
        if (audioRecorder.recording()) return;
        deletePreviewAudio();
        try {
            Path folder = Paths.get("storage", "audios");
            Files.createDirectories(folder);
            previewAudioFile = folder.resolve("recording-" + UUID.randomUUID() + ".wav").toFile();
            audioRecorder.start(previewAudioFile);
            recordingStartedNanos = System.nanoTime();
            recordingDurationLabel.setText("00:00");
            composerBox.setVisible(false);
            composerBox.setManaged(false);
            recordingBar.setVisible(true);
            recordingBar.setManaged(true);
            statusLabel.setText("Enregistrement en cours.");
            recordingTimeline.playFromStart();
        } catch (javax.sound.sampled.LineUnavailableException e) {
            statusLabel.setText("Microphone indisponible ou accès refusé.");
        } catch (Exception e) {
            statusLabel.setText("Impossible de démarrer l'enregistrement audio.");
        }
    }

    // Finalise le WAV dans un thread secondaire, puis affiche l'apercu sans bloquer JavaFX.
    @FXML private void stopRecording() {
        if (!audioRecorder.recording()) return;
        recordingTimeline.stop();
        CompletableFuture.runAsync(() -> audioRecorder.stop(), audioExecutor).whenComplete((ignored, error) ->
            Platform.runLater(() -> {
                recordingBar.setVisible(false);
                recordingBar.setManaged(false);
                if (error != null) {
                    statusLabel.setText("Erreur pendant la finalisation du fichier audio.");
                    deletePreviewAudio();
                    showComposer();
                    return;
                }
                previewDurationMillis = audioRecorder.durationMillis();
                if (previewAudioFile == null || previewAudioFile.length() <= 44
                        || previewDurationMillis < 250) {
                    statusLabel.setText("Enregistrement trop court.");
                    deletePreviewAudio();
                    showComposer();
                    return;
                }
                previewDurationLabel.setText(formatDurationMillis(previewDurationMillis));
                previewSlider.setValue(0);
                setupPreviewPlayer();
                previewBar.setVisible(true);
                previewBar.setManaged(true);
                statusLabel.setText("Écoutez votre message avant l'envoi.");
            }));
    }

    @FXML private void cancelRecording() {
        recordingTimeline.stop();
        if (audioRecorder.recording()) {
            CompletableFuture.runAsync(() -> audioRecorder.stop(), audioExecutor)
                    .thenRun(() -> Platform.runLater(this::deletePreviewAudio));
        } else {
            deletePreviewAudio();
        }
        recordingBar.setVisible(false);
        recordingBar.setManaged(false);
        showComposer();
        statusLabel.setText("Enregistrement annulé.");
    }

    @FXML private void togglePreviewPlayback() {
        if (previewPlayer == null) return;
        if (previewPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            previewPlayer.pause();
            previewPlayButton.setText("▶");
        } else {
            previewPlayer.play();
            previewPlayButton.setText("❚❚");
        }
    }

    // Lit le WAV, l'encode en Base64 et le place dans la meme file TCP que les autres fichiers.
    // On ne transmet jamais uniquement le chemin local.
    @FXML private void sendRecording() {
        if (recipient == null || previewAudioFile == null) return;
        File audioToSend = previewAudioFile;
        long durationToSend = previewDurationMillis;
        previewPlayButton.setDisable(true);
        client.sendAudio(recipient, audioToSend, durationToSend).thenAccept(message ->
            Platform.runLater(() -> {
                conversations.computeIfAbsent(recipient, ignored -> new ArrayList<>()).add(message);
                lastMessages.put(recipient, message);
                usersList.refresh();
                renderMessage(message);
                previewPlayButton.setDisable(false);
                deletePreviewAudio();
                showComposer();
                statusLabel.setText("Message vocal envoyé.");
            })).exceptionally(error -> {
                Platform.runLater(() -> {
                    previewPlayButton.setDisable(false);
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    statusLabel.setText(cause.getMessage() == null
                            ? "Impossible d'envoyer le message vocal." : cause.getMessage());
                });
                return null;
            });
    }

    @FXML private void deleteRecording() {
        deletePreviewAudio();
        previewBar.setVisible(false);
        previewBar.setManaged(false);
        showComposer();
        statusLabel.setText("Aperçu supprimé.");
    }

    private void setupPreviewPlayer() {
        if (previewAudioFile == null || !previewAudioFile.isFile()) return;
        disposePreviewPlayer();
        try {
            previewPlayer = new MediaPlayer(new Media(previewAudioFile.toURI().toString()));
            previewPlayer.setOnEndOfMedia(() -> {
                previewPlayer.seek(Duration.ZERO);
                previewPlayer.stop();
                previewPlayButton.setText("▶");
                previewSlider.setValue(0);
            });
            previewPlayer.currentTimeProperty().addListener((obs, oldTime, currentTime) ->
                previewSlider.setValue(previewDurationMillis <= 0 ? 0
                    : Math.max(0, Math.min(100,
                        currentTime.toMillis() * 100.0 / previewDurationMillis))));
            previewSlider.setOnMouseReleased(event -> {
                if (previewPlayer != null) {
                    previewPlayer.seek(Duration.millis(
                            previewDurationMillis * previewSlider.getValue() / 100.0));
                }
            });
        } catch (Exception e) {
            previewPlayer = null;
            statusLabel.setText("Impossible de lire l'aperçu audio.");
        }
    }

    private void showComposer() {
        composerBox.setVisible(true);
        composerBox.setManaged(true);
    }

    private void deletePreviewAudio() {
        disposePreviewPlayer();
        previewDurationMillis = 0;
        if (previewAudioFile != null) {
            try { Files.deleteIfExists(previewAudioFile.toPath()); }
            catch (Exception ignored) { }
        }
        previewAudioFile = null;
        previewSlider.setValue(0);
        if (previewPlayButton != null) previewPlayButton.setText("▶");
    }

    private void renderConversation() {
        messagesBox.getChildren().clear();
        conversations.getOrDefault(recipient, List.of()).forEach(this::renderMessage);
    }

    private void renderMessage(Message message) {
        javafx.scene.Node bubble;
        if ("IMAGE".equals(message.type())) {
            bubble = imageBubble(message);
        } else if ("AUDIO".equals(message.type())) {
            bubble = audioBubble(message);
        } else if ("FILE".equals(message.type())) {
            bubble = fileBubble(message);
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

    private javafx.scene.Node audioBubble(Message message) {
        // Sauvegarde le WAV recu dans storage/audios, puis cree un MediaPlayer local.
        try {
            byte[] bytes = Base64.getDecoder().decode(message.contentBase64());
            Path folder = Paths.get("storage", "audios");
            Files.createDirectories(folder);
            Path audioPath = folder.resolve(message.id() + "-"
                    + Paths.get(message.fileName()).getFileName());
            Files.write(audioPath, bytes);

            HBox bubble = new HBox();
            bubble.getStyleClass().addAll("audio-bubble",
                    message.sender().equals(client.user().id()) ? "message-out" : "message-in");
            Button playButton = new Button("▶");
            playButton.getStyleClass().add("icon-button");
            Slider progress = new Slider(0, 100, 0);
            progress.setPrefWidth(180);
            Label duration = new Label(formatDurationMillis(message.durationMillis()));
            duration.getStyleClass().add("audio-duration");
            bubble.getChildren().addAll(playButton, progress, duration);

            MediaPlayer player = new MediaPlayer(new Media(audioPath.toUri().toString()));
            audioPlayers.put(message.id(), player);
            playButton.setOnAction(event -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    player.pause();
                    playButton.setText("▶");
                } else {
                    player.play();
                    playButton.setText("❚❚");
                }
            });
            player.currentTimeProperty().addListener((obs, oldTime, currentTime) -> {
                if (message.durationMillis() > 0) {
                    progress.setValue(Math.max(0, Math.min(100,
                            currentTime.toMillis() * 100.0 / message.durationMillis())));
                }
            });
            player.setOnEndOfMedia(() -> {
                player.seek(Duration.ZERO);
                player.stop();
                playButton.setText("▶");
                progress.setValue(0);
            });
            progress.setOnMouseReleased(event -> {
                if (message.durationMillis() > 0) {
                    player.seek(Duration.millis(
                            message.durationMillis() * progress.getValue() / 100.0));
                }
            });
            player.setOnError(() -> statusLabel.setText("Impossible de lire ce message vocal."));
            return bubble;
        } catch (Exception e) {
            return new Label("Message vocal indisponible.");
        }
    }

    /**
     * Affiche les fichiers recus sous forme de carte, puis sauvegarde les octets localement.
     * Hna kan7afdo le binaire reçu dans un dossier temporaire système
     * bach l'utilisateur ydir open/download sans ajouter le fichier au projet.
     */
    private javafx.scene.Node fileBubble(Message message) {
        try {
            byte[] bytes = Base64.getDecoder().decode(message.contentBase64());
            if (bytes.length != message.fileSize()) throw new IOException("Taille du fichier reçue incorrecte.");
            if (temporaryAttachmentDirectory == null) {
                temporaryAttachmentDirectory = Files.createDirectories(
                        Paths.get(System.getProperty("java.io.tmpdir"), "LinaChat", "attachments"));
            }
            String safeName = Paths.get(message.fileName()).getFileName().toString();
            // Stockage hors du projet dans le dossier temporaire du système d'exploitation.
            // Hna le fichier reste associé au message, pas ajoute aux ressources Java du projet.
            Path filePath = temporaryAttachmentDirectory.resolve(message.id() + "-" + safeName);
            Files.write(filePath, bytes);

            String lower = safeName.toLowerCase(Locale.ROOT);
            String extension = lower.contains(".")
                    ? lower.substring(lower.lastIndexOf('.') + 1) : "fichier";
            String icon = switch (extension) {
                case "pdf" -> "PDF";
                case "doc", "docx" -> "DOC";
                case "xls", "xlsx", "csv" -> "XLS";
                case "ppt", "pptx" -> "PPT";
                case "zip", "rar", "7z" -> "ZIP";
                default -> extension.length() > 4
                        ? extension.substring(0, 4).toUpperCase(Locale.ROOT)
                        : extension.toUpperCase(Locale.ROOT);
            };

            VBox card = new VBox(6);
            card.getStyleClass().addAll("file-card",
                    message.sender().equals(client.user().id()) ? "message-out" : "message-in");
            Label iconLabel = new Label(icon);
            iconLabel.getStyleClass().add("file-icon");
            Label nameLabel = new Label(safeName);
            nameLabel.getStyleClass().add("file-name");
            nameLabel.setWrapText(true);
            Label details = new Label("." + extension + "  •  " + formatFileSize(message.fileSize()));
            details.getStyleClass().add("file-details");
            HBox actions = new HBox(8);
            Button openButton = new Button("Ouvrir");
            openButton.getStyleClass().add("secondary-button");
            openButton.setOnAction(event -> openFile(filePath));
            Button downloadButton = new Button("Télécharger");
            downloadButton.getStyleClass().add("secondary-button");
            downloadButton.setOnAction(event -> downloadFile(filePath, safeName));
            actions.getChildren().addAll(openButton, downloadButton);
            card.getChildren().addAll(iconLabel, nameLabel, details, actions);
            return card;
        } catch (Exception e) {
            return new Label("Fichier invalide ou indisponible.");
        }
    }

    private void openFile(Path filePath) {
        try {
            if (!Files.isRegularFile(filePath)) throw new IOException("Fichier introuvable.");
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(filePath.toFile());
                statusLabel.setText("Fichier ouvert.");
            } else {
                statusLabel.setText("Aucune application système disponible pour ouvrir ce fichier.");
            }
        } catch (Exception e) {
            statusLabel.setText("Impossible d'ouvrir le fichier.");
        }
    }

    private void downloadFile(Path filePath, String originalName) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(originalName);
        File destination = chooser.showSaveDialog(messageField.getScene().getWindow());
        if (destination == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                Files.copy(filePath, destination.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Platform.runLater(() -> statusLabel.setText("Fichier téléchargé."));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Téléchargement impossible."));
            }
        });
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return String.format(Locale.FRANCE, "%.1f Ko", bytes / 1024.0);
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
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
                if (event.getClickCount() < 1) return;
                try {
                    Alert viewer = new Alert(Alert.AlertType.INFORMATION);
                    viewer.setHeaderText(message.fileName());
                    ImageView enlarged = new ImageView(new Image(new ByteArrayInputStream(bytes)));
                    enlarged.setPreserveRatio(true);
                    enlarged.setFitWidth(720);
                    enlarged.setFitHeight(520);
                    viewer.getDialogPane().setContent(enlarged);
                    viewer.getDialogPane().getStylesheets().add(
                            getClass().getResource("/com/linachat/css/theme.css").toExternalForm());
                    viewer.showAndWait();
                } catch (Exception ignored) {
                    statusLabel.setText("Impossible d'ouvrir l'image.");
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

    private String formatDurationMillis(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.FRANCE, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void disposePreviewPlayer() {
        if (previewPlayer != null) {
            previewPlayer.stop();
            previewPlayer.dispose();
            previewPlayer = null;
        }
    }

    public void disposeAudioResources() {
        recordingTimeline.stop();
        if (audioRecorder.recording()) {
            CompletableFuture.runAsync(() -> audioRecorder.stop(), audioExecutor)
                    .exceptionally(error -> null);
        }
        disposePreviewPlayer();
        audioPlayers.values().forEach(player -> {
            player.stop();
            player.dispose();
        });
        audioPlayers.clear();
        audioRecorder.close();
        audioExecutor.shutdownNow();
        if (temporaryAttachmentDirectory != null) {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporaryAttachmentDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) { }
        }
    }
    // Les actions JavaFX doivent rester sur le thread graphique. Ici, le bouton ouvre
    // seulement les parametres locaux du profil et informe l'utilisateur sans blocage.
    @FXML private void openSettings() { statusLabel.setText("Paramètres du profil disponibles prochainement."); }

    /** Envoie une trame DISCONNECT puis conserve la Socket fermée par le serveur. */
    @FXML private void disconnect() {
        if (client != null) client.disconnect();
        statusLabel.setText("Déconnexion en cours...");
    }
}
