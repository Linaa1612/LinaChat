package com.linachat;

import com.linachat.client.ChatClient;
import com.linachat.model.User;
import com.linachat.server.ChatServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class ChatIntegrationTest {
    // Ce test demarre un vrai serveur TCP sur un port temporaire,
    // puis verifie qu'un message passe entre deux clients distincts.
    @Test
    void profilesContactsAndMessagesAreRouted() throws Exception {
        ChatServer server = new ChatServer(0);
        Thread serverThread = new Thread(() -> {
            try { server.start(); } catch (Exception ignored) { }
        });
        serverThread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.localPort() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(server.localPort() > 0);
            ChatClient alice = new ChatClient();
            ChatClient bob = new ChatClient();
            User aliceProfile = User.create("Alice", "Disponible", null);
            User bobProfile = User.create("Bob", "Disponible", null);
            CountDownLatch bobReceived = new CountDownLatch(1);
            bob.onMessage(message -> { if ("Bonjour Bob".equals(message.text())) bobReceived.countDown(); });
            alice.connect("127.0.0.1", server.localPort(), aliceProfile).get(3, TimeUnit.SECONDS);
            bob.connect("127.0.0.1", server.localPort(), bobProfile).get(3, TimeUnit.SECONDS);
            alice.sendText(bobProfile.id(), "Bonjour Bob");
            assertTrue(bobReceived.await(3, TimeUnit.SECONDS));

            // Le meme protocole transporte les fichiers generiques, dont les fichiers audio WAV.
            CountDownLatch fileReceived = new CountDownLatch(1);
            CountDownLatch audioReceived = new CountDownLatch(1);
            bob.onMessage(message -> {
                if ("document.txt".equals(message.fileName())) fileReceived.countDown();
                if ("voix.wav".equals(message.fileName())) audioReceived.countDown();
            });
            Path document = Files.createTempDirectory("linachat").resolve("document.txt");
            Files.writeString(document, "Piece jointe LinaChat");
            alice.sendFile(bobProfile.id(), document.toFile());
            assertTrue(fileReceived.await(3, TimeUnit.SECONDS));
            Path audio = Files.createTempDirectory("linachat").resolve("voix.wav");
            Files.write(audio, new byte[]{82,73,70,70,36,0,0,0,87,65,86,69});
            alice.sendFile(bobProfile.id(), audio.toFile());
            assertTrue(audioReceived.await(3, TimeUnit.SECONDS));

            // Une petite image PNG valide est envoyée via Base64 dans une trame MESSAGE.
            CountDownLatch imageReceived = new CountDownLatch(1);
            bob.onMessage(message -> {
                if ("IMAGE".equals(message.type()) && "pixel.png".equals(message.fileName())) {
                    imageReceived.countDown();
                }
            });
            Path image = Files.createTempDirectory("linachat").resolve("pixel.png");
            BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(pixel, "png", png);
            Files.write(image, png.toByteArray());
            alice.sendImage(bobProfile.id(), image.toFile()).get(3, TimeUnit.SECONDS);
            assertTrue(imageReceived.await(3, TimeUnit.SECONDS));
            alice.close();
            bob.close();
        } finally {
            server.close();
            serverThread.join(1000);
        }
    }
}
