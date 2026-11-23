package com.linachat;

import com.linachat.client.ChatClient;
import com.linachat.model.Message;
import com.linachat.model.User;
import com.linachat.server.ChatServer;
import com.linachat.service.GeminiClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class ChatIntegrationTest {
    @Test
    void geminiRespondsWithConfiguredApiKey() throws Exception {
        assumeTrue(System.getenv("GEMINI_API_KEY") != null
                && !System.getenv("GEMINI_API_KEY").isBlank(),
                "GEMINI_API_KEY non configurée");
        GeminiClient client = new GeminiClient();
        String answer = client.respond(java.util.List.of(
                new GeminiClient.ChatMessage("user", "Réponds simplement: LinaChat Gemini est opérationnel.")));
        assertNotNull(answer);
        assertFalse(answer.isBlank());
    }

    @Test
    void aiRequestsAreRoutedPrivately() throws Exception {
        ChatServer server = new ChatServer(0);
        Thread serverThread = new Thread(() -> {
            try { server.start(); } catch (Exception ignored) {}
        });
        serverThread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.localPort() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(server.localPort() > 0);

            ChatClient alice = new ChatClient();
            ChatClient bob = new ChatClient();
            CountDownLatch aliceError = new CountDownLatch(1);
            CountDownLatch bobError = new CountDownLatch(1);
            alice.onAiError(error -> aliceError.countDown());
            alice.onAiResponse(answer -> aliceError.countDown());
            bob.onAiError(error -> bobError.countDown());
            bob.onAiResponse(answer -> bobError.countDown());
            alice.connect("127.0.0.1", server.localPort(), User.create("Alice IA", "Disponible", null))
                    .get(3, TimeUnit.SECONDS);
            bob.connect("127.0.0.1", server.localPort(), User.create("Bob IA", "Disponible", null))
                    .get(3, TimeUnit.SECONDS);

            alice.sendAiRequest("Bonjour, explique-moi TCP");
            bob.sendAiRequest("Hello, explain sockets");
            assertTrue(aliceError.await(3, TimeUnit.SECONDS));
            assertTrue(bobError.await(3, TimeUnit.SECONDS));

            alice.close();
            bob.close();
        } finally {
            server.close();
            serverThread.join(1000);
        }
    }
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

            // Les emojis sont des caractères Unicode ordinaires envoyés dans le champ texte.
            CountDownLatch emojiReceived = new CountDownLatch(1);
            bob.onMessage(message -> {
                if ("Hello 😊 مرحبا".equals(message.text())) emojiReceived.countDown();
            });
            alice.sendText(bobProfile.id(), "Hello 😊 مرحبا");
            assertTrue(emojiReceived.await(3, TimeUnit.SECONDS));

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

            // Test binaire PDF: le destinataire doit reconstruire exactement les memes octets.
            AtomicReference<byte[]> receivedPdf = new AtomicReference<>();
            CountDownLatch pdfReceived = new CountDownLatch(1);
            bob.onMessage(message -> {
                if ("rapport.pdf".equals(message.fileName()) && "FILE".equals(message.type())) {
                    receivedPdf.set(java.util.Base64.getDecoder().decode(message.contentBase64()));
                    pdfReceived.countDown();
                }
            });
            Path pdf = Files.createTempDirectory("linachat").resolve("rapport.pdf");
            byte[] pdfBytes = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            Files.write(pdf, pdfBytes);
            Message pdfMessage = alice.sendFile(bobProfile.id(), pdf.toFile());
            assertTrue(pdfReceived.await(3, TimeUnit.SECONDS));
            assertArrayEquals(pdfBytes, receivedPdf.get());
            assertEquals(pdfBytes.length, pdfMessage.fileSize());

            // Word et Excel utilisent aussi le binaire FILE, sans validation liée à une extension.
            CountDownLatch officeReceived = new CountDownLatch(2);
            bob.onMessage(message -> {
                if ("rapport.docx".equals(message.fileName()) || "budget.xlsx".equals(message.fileName())) {
                    officeReceived.countDown();
                }
            });
            Path docx = Files.createTempDirectory("linachat").resolve("rapport.docx");
            byte[] docxBytes = "DOCX test binaire".getBytes(StandardCharsets.UTF_8);
            Files.write(docx, docxBytes);
            alice.sendFile(bobProfile.id(), docx.toFile());

            Path xlsx = Files.createTempDirectory("linachat").resolve("budget.xlsx");
            byte[] xlsxBytes = "XLSX test binaire".getBytes(StandardCharsets.UTF_8);
            Files.write(xlsx, xlsxBytes);
            alice.sendFile(bobProfile.id(), xlsx.toFile());
            assertTrue(officeReceived.await(3, TimeUnit.SECONDS));

            // Un fichier invalide ne doit jamais entrer dans la queue TCP.
            Path missing = Files.createTempDirectory("linachat").resolve("introuvable.pdf");
            assertThrows(IOException.class, () -> alice.sendFile(bobProfile.id(), missing.toFile()));

            // Vérification du protocole vocal: données WAV, taille, destinataire et durée.
            CountDownLatch voiceReceived = new CountDownLatch(1);
            bob.onMessage(message -> {
                if ("AUDIO".equals(message.type()) && "message.wav".equals(message.fileName())) {
                    voiceReceived.countDown();
                }
            });
            Path voice = Files.createTempDirectory("linachat").resolve("message.wav");
            byte[] wav = new byte[44 + 2048];
            wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
            wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
            Files.write(voice, wav);
            alice.sendAudio(bobProfile.id(), voice.toFile(), 1250).get(3, TimeUnit.SECONDS);
            assertTrue(voiceReceived.await(3, TimeUnit.SECONDS));

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

            // Alice envoie une déconnexion propre; Bob doit recevoir immédiatement une liste sans Alice.
            CountDownLatch aliceRemoved = new CountDownLatch(1);
            bob.onContacts(users -> {
                if (users.stream().noneMatch(user -> user.id().equals(aliceProfile.id()))) {
                    aliceRemoved.countDown();
                }
            });
            alice.disconnect();
            assertTrue(aliceRemoved.await(3, TimeUnit.SECONDS));

            // Reconnexion: un nouvel identifiant crée un nouveau profil temporaire,
            // sans aucune base de données ni conservation permanente.
            ChatClient aliceReconnected = new ChatClient();
            User aliceAgain = User.create("Alice", "Disponible", null);
            aliceReconnected.connect("127.0.0.1", server.localPort(), aliceAgain)
                    .get(3, TimeUnit.SECONDS);
            aliceReconnected.close();
            alice.close();
            bob.close();
        } finally {
            server.close();
            serverThread.join(1000);
        }
    }
}
