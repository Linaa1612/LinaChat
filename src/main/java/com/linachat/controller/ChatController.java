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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.util.Duration;

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
    }
    // Les actions JavaFX doivent rester sur le thread graphique. Ici, le bouton ouvre
    // seulement les parametres locaux du profil et informe l'utilisateur sans blocage.
    @FXML private void openSettings() { statusLabel.setText("Paramètres du profil disponibles prochainement."); }
}
