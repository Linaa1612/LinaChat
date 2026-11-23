module com.linachat {
    // Modules JavaFX utilises par les vues FXML et les controles graphiques.
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    // Jackson permet de serialiser les profils, messages et trames JSON sur le TCP.
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    // java.desktop fournit les outils Swing et ImageIO utilises pour valider les images.
    requires java.desktop;
    requires java.net.http;
    exports com.linachat;
    exports com.linachat.controller;
    opens com.linachat.controller to javafx.fxml;
    opens com.linachat.model to com.fasterxml.jackson.databind;
}
