package com.linachat.android;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Enregistre du PCM mono 16 bits et produit le WAV demandé par le serveur. */
final class WavRecorder {
    private static final int SAMPLE_RATE = 44100;
    private static final long MAX_PCM_BYTES = 14L * 1024 * 1024;
    private AudioRecord recorder;
    private Thread recordingThread;
    private File pcmFile;
    private File wavFile;
    private volatile boolean recording;
    private volatile long durationMillis;
    private volatile IOException captureError;
    private long startedAt;

    synchronized void start(File directory) throws IOException {
        if (recording) throw new IllegalStateException("Un enregistrement est déjà en cours.");
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) throw new IOException("Format d'enregistrement audio indisponible.");
        pcmFile = File.createTempFile("linachat-", ".pcm", directory);
        wavFile = new File(directory, "voice-" + System.currentTimeMillis() + ".wav");
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(bufferSize, 4096));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            throw new IOException("Impossible d'initialiser le microphone.");
        }

        recording = true;
        captureError = null;
        startedAt = System.nanoTime();
        recorder.startRecording();
        AudioRecord activeRecorder = recorder;
        recordingThread = new Thread(() -> capture(activeRecorder, pcmFile),
                "linachat-audio-recorder");
        recordingThread.start();
    }

    private void capture(AudioRecord activeRecorder, File destination) {
        byte[] buffer = new byte[4096];
        long total = 0;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            while (recording && total < MAX_PCM_BYTES) {
                int count = activeRecorder.read(buffer, 0, buffer.length);
                if (count > 0) {
                    output.write(buffer, 0, count);
                    total += count;
                } else if (count < 0 && recording) {
                    throw new IOException("Erreur pendant la capture audio.");
                }
            }
        } catch (IOException error) {
            captureError = error;
            recording = false;
        } finally {
            recording = false;
        }
    }

    synchronized File stop() throws Exception {
        if (recorder == null || pcmFile == null) {
            throw new IllegalStateException("Aucun enregistrement actif.");
        }
        recording = false;
        durationMillis = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        try {
            recorder.stop();
        } catch (IllegalStateException ignored) {
            // L'enregistreur peut déjà être arrêté après avoir atteint la limite de taille.
        }
        recorder.release();
        recorder = null;
        if (recordingThread != null) {
            recordingThread.join(5000);
            if (recordingThread.isAlive()) throw new IOException("La capture audio ne s'est pas arrêtée.");
            recordingThread = null;
        }
        if (captureError != null) {
            throw new IOException("Erreur pendant la capture audio.", captureError);
        }

        long pcmLength = pcmFile.length();
        if (pcmLength <= 0 || pcmLength > MAX_PCM_BYTES) {
            throw new IOException("L'enregistrement est vide ou trop volumineux.");
        }
        writeHeader(wavFile, pcmLength);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(pcmFile));
             BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(wavFile, true))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        if (!pcmFile.delete()) pcmFile.deleteOnExit();
        return wavFile;
    }

    long durationMillis() {
        return durationMillis;
    }

    private void writeHeader(File destination, long pcmLength) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(destination))) {
            output.writeBytes("RIFF");
            writeLittleEndianInt(output, (int) (36 + pcmLength));
            output.writeBytes("WAVE");
            output.writeBytes("fmt ");
            writeLittleEndianInt(output, 16);
            writeLittleEndianShort(output, 1);
            writeLittleEndianShort(output, 1);
            writeLittleEndianInt(output, SAMPLE_RATE);
            writeLittleEndianInt(output, SAMPLE_RATE * 2);
            writeLittleEndianShort(output, 2);
            writeLittleEndianShort(output, 16);
            output.writeBytes("data");
            writeLittleEndianInt(output, (int) pcmLength);
        }
    }

    private void writeLittleEndianInt(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
        output.writeByte((value >>> 16) & 0xff);
        output.writeByte((value >>> 24) & 0xff);
    }

    private void writeLittleEndianShort(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
    }

    synchronized void cancel() {
        if (recorder != null) {
            recording = false;
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // L'enregistreur peut déjà être arrêté.
            }
            recorder.release();
            recorder = null;
        }
        if (recordingThread != null) {
            try {
                recordingThread.join(2000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        if (pcmFile != null) pcmFile.delete();
        if (wavFile != null) wavFile.delete();
        pcmFile = null;
        wavFile = null;
        captureError = null;
    }
}
