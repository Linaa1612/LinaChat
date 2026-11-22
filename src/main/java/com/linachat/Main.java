package com.linachat;

import com.linachat.controller.LoginController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Point d'entree JavaFX de LinaChat.
 * Cette classe charge le FXML de connexion, puis applique le theme CSS commun.
 */
public final class Main extends Application {
    // JavaFX appelle cette methode sur l'application thread pour construire la premiere Scene.
    @Override public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linachat/fxml/Login.fxml"));
        Scene scene = new Scene(loader.load(), 520, 680);
        scene.getStylesheets().add(getClass().getResource("/com/linachat/css/theme.css").toExternalForm());
        stage.setTitle("LinaChat"); stage.setScene(scene);
        stage.setMinWidth(420);
        stage.setMinHeight(560);
        stage.show();
    }
    public static void main(String[] args) { launch(args); }
}
