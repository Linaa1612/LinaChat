package com.linachat;

import com.linachat.server.ChatServer;

public final class ServerMain {
    // Le serveur utilise le premier argument comme port ou 5050 par defaut.
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 5050 : Integer.parseInt(args[0]);
        try (ChatServer server = new ChatServer(port)) {
            server.start();
            Thread.currentThread().join();
        }
    }
}
