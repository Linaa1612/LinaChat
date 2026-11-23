package com.linachat.service;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;

/**
 * Enregistre la voix du microphone dans un fichier WAV.
 * Hna capture l'utilisateur micro dans un thread separe, pas dans JavaFX Application Thread.
 */
public final class AudioRecorder implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 1, true, false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TargetDataLine line;
    private Future<?> task;
    private volatile boolean recording;
    private volatile long durationMillis;
    private volatile long startNanos;

    /**
     * Ouvre le micro puis lance la capture PCM hors du thread graphique.
     * @param destination fichier WAV temporaire destination
     * @throws LineUnavailableException si aucune ligne micro compatible n'est disponible
     * @throws IOException si le fichier WAV ne peut pas etre cree
     */
    public synchronized void start(File destination) throws LineUnavailableException, IOException {
        if (task != null && !task.isDone()) throw new IllegalStateException("Un enregistrement est déjà en cours");
        line = AudioSystem.getTargetDataLine(FORMAT);
        line.open(FORMAT);
        line.start();
        recording = true;
        startNanos = System.nanoTime();

        task = executor.submit(() -> {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            try {
                while (recording && line.isOpen()) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read > 0) pcm.write(buffer, 0, read);
                }
                byte[] bytes = pcm.toByteArray();
                if (bytes.length > 44) {
                    long frames = bytes.length / FORMAT.getFrameSize();
                    try (AudioInputStream input = new AudioInputStream(
                            new ByteArrayInputStream(bytes), FORMAT, frames)) {
                        AudioSystem.write(input, AudioFileFormat.Type.WAVE, destination);
                    }
                }
            } catch (IOException e) {
                throw new CompletionException("Impossible d'ecrire le fichier WAV.", e);
            }
        });
    }

    /** Ferme proprement la ligne micro puis attend que le fichier WAV soit finalise. */
    public synchronized void stop() {
        if (!recording) return;
        recording = false;
        durationMillis = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
        if (task != null) {
            try {
                task.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Enregistrement interrompu.", e);
            } catch (ExecutionException e) {
                throw new CompletionException(e.getCause());
            } catch (TimeoutException e) {
                throw new CompletionException("Le fichier audio n'a pas ete finalise a temps.", e);
            }
        }
    }

    /** @return duree mesuree pendant l'enregistrement */
    public long durationMillis() { return durationMillis; }
    public synchronized boolean recording() { return line != null && line.isOpen(); }
    @Override public void close() {
        try { stop(); } finally { executor.shutdownNow(); }
    }
}
