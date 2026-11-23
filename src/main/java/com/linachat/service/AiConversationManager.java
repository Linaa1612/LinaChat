package com.linachat.service;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Gère le contexte IA en mémoire, séparé des conversations privées.
 * Hna kol user 3ando historique wahed b rasso; mabghinach n3aytow messages dyal ghi3.
 * Les données disparaissent au redémarrage ou lors de la suppression de l'utilisateur.
 */
public final class AiConversationManager {
    private final ConcurrentHashMap<String, Deque<GeminiClient.ChatMessage>> histories =
            new ConcurrentHashMap<>();
    private final int maxMessages;

    public AiConversationManager() {
        int configured = 20;
        try {
            configured = Integer.parseInt(System.getenv().getOrDefault("AI_MAX_HISTORY_MESSAGES", "20"));
        } catch (Exception ignored) {}
        this.maxMessages = Math.max(2, configured);
    }

    public synchronized List<GeminiClient.ChatMessage> appendUser(String userId, String text) {
        Deque<GeminiClient.ChatMessage> history =
                histories.computeIfAbsent(userId, ignored -> new ConcurrentLinkedDeque<>());
        history.addLast(new GeminiClient.ChatMessage("user", text));
        trim(history);
        return new ArrayList<>(history);
    }

    public synchronized void appendAssistant(String userId, String text) {
        Deque<GeminiClient.ChatMessage> history =
                histories.computeIfAbsent(userId, ignored -> new ConcurrentLinkedDeque<>());
        history.addLast(new GeminiClient.ChatMessage("assistant", text));
        trim(history);
    }

    public void clear(String userId) {
        histories.remove(userId);
    }

    private void trim(Deque<GeminiClient.ChatMessage> history) {
        while (history.size() > maxMessages) history.removeFirst();
    }
}
