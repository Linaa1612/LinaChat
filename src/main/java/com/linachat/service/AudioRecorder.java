package com.linachat.service;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;

/** Enregistre le microphone dans un WAV sans utiliser le thread JavaFX. */
public final class AudioRecorder implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 1, true, false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TargetDataLine line; private Future<?> task;
    public synchronized void start(File destination) throws LineUnavailableException, IOException {
        if (task != null && !task.isDone()) throw new IllegalStateException("Un enregistrement est déjà en cours");
        line = AudioSystem.getTargetDataLine(FORMAT); line.open(FORMAT); line.start();
        task = executor.submit(() -> { try (AudioInputStream input = new AudioInputStream(line)) { AudioSystem.write(input, AudioFileFormat.Type.WAVE, destination); } catch (IOException e) { throw new CompletionException(e); } });
    }
    public synchronized void stop() { if (line != null) { line.stop(); line.close(); line = null; } }
    public synchronized boolean recording() { return line != null && line.isOpen(); }
    @Override public void close() { stop(); executor.shutdownNow(); }
}
