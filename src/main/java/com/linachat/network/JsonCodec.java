package com.linachat.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.*;
import java.nio.charset.StandardCharsets;

//convertir les objets Java en JSON pour les envoyer entre le client et le serveur, puis de récupérer les messages JSON reçus.
public final class JsonCodec {
    // MAPPER configure Jackson pour convertir Instant et les objets Java en JSON.
    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private JsonCodec() {}
    public static synchronized void write(OutputStream out, Object value) throws IOException {
        // Le protocole ecrit d'abord la taille UTF-8 sur 4 octets, puis le JSON.
        byte[] data = MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(data.length);
        dos.write(data);
        dos.flush();
    }
    public static String read(InputStream in) throws IOException {
        // Cette lecture inverse write(): on prend la taille, puis exactement le nombre d'octets annonce.
        DataInputStream dis = new DataInputStream(in);
        int length = dis.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("Taille de trame invalide");
        byte[] data = dis.readNBytes(length);
        if (data.length != length) throw new EOFException();
        return new String(data, StandardCharsets.UTF_8);
    }
}
