package com.linachat.controller;

import com.linachat.client.ChatClient;
import com.linachat.model.User;
import com.linachat.service.AppConfig;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.nio.file.Files;
import java.util.Base64;
import javax.imageio.ImageIO;

public final class LoginController {
    // Ces champs correspondent aux fx:id du Login.fxml.
    @FXML private TextField usernameField;
    @FXML private Label statusLabel;
    @FXML private Button connectButton;
    private String avatarBase64;
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;

    @FXML private void chooseAvatar() {
        // L'image est validee avant conversion Base64 pour limiter la taille du profil.
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        java.io.File file = chooser.showOpenDialog(usernameField.getScene().getWindow());
        if (file == null) return;
        try {
            if (file.length() > MAX_AVATAR_BYTES || ImageIO.read(file) == null) {
                statusLabel.setText("Photo invalide ou trop volumineuse (5 Mo maximum).");
                return;
            }
            avatarBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            statusLabel.setText("Photo de profil sélectionnée.");
        } catch (Exception e) {
            statusLabel.setText("Impossible de charger la photo.");
        }
    }

    @FXML private void connect(ActionEvent event) {
        // Hna kanconstructiw profil puis kanconnectiw l'utilisateur au serveur
        // sans bloquer JavaFX. Le resultat est traite avec Platform.runLater.
        String name = usernameField.getText().trim();
        if (name.isBlank()) { statusLabel.setText("Saisissez votre nom."); return; }
        connectButton.setDisable(true);
        statusLabel.setText("Connexion en cours...");
        try {
            AppConfig config = new AppConfig();
            User profile = User.create(name, "", avatarBase64);
            ChatClient client = new ChatClient();
            client.connect(config.get("server.host", "127.0.0.1"),
                            Integer.parseInt(config.get("server.port", "5050")), profile)
                    .thenAccept(user -> Platform.runLater(() -> openChat(client, user)))
                    .exceptionally(error -> {
                        Platform.runLater(() -> {
                            connectButton.setDisable(false);
                            statusLabel.setText("Impossible de se connecter. Veuillez réessayer.");
                        });
                        return null;
                    });
        } catch (Exception e) {
            connectButton.setDisable(false);
            statusLabel.setText("Impossible de se connecter. Veuillez réessayer.");
        }
    }

    private void openChat(ChatClient client, User user) {
        // Apres la connexion, le login est remplace par le Chat.fxml
        // et le meme client TCP est injecte dans le ChatController.
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linachat/fxml/Chat.fxml"));
            Scene scene = new Scene(loader.load(), 1100, 720);
            loader.<ChatController>getController().initializeClient(client);
            scene.getStylesheets().add(getClass().getResource("/com/linachat/css/theme.css").toExternalForm());
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setTitle("LinaChat - " + user.name());
            stage.setScene(scene);
            stage.setMinWidth(850);
            stage.setMinHeight(560);
        } catch (Exception e) {
            connectButton.setDisable(false);
            statusLabel.setText("Impossible de charger LinaChat.");
        }
    }
}
