package com.linachat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linachat.network.JsonCodec;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * Client HTTPS de l'API REST Google Gemini.
 * La clé est lue uniquement depuis GEMINI_API_KEY et les appels sont effectués côté serveur.
 */
public final class GeminiClient {
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final String fallbackModel;
    private final Duration timeout;

    public GeminiClient() {
        String configuredKey = System.getenv("GEMINI_API_KEY");
        this.apiKey = configuredKey == null ? null : configuredKey.trim();
        String configuredModel = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.8-flash").trim();
        this.model = configuredModel.isBlank() ? "gemini-3.8-flash" : configuredModel;
        String configuredFallback =
                System.getenv().getOrDefault("GEMINI_FALLBACK_MODEL", "gemini-3.5-flash-lite").trim();
        this.fallbackModel =
                configuredFallback.isBlank() ? "gemini-3.5-flash-lite" : configuredFallback;
        this.timeout = Duration.ofSeconds(parsePositive("GEMINI_TIMEOUT_SECONDS", 60));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    public String respond(List<ChatMessage> history) throws Exception {
        if (!configured()) {
            throw new GeminiException("MISSING_KEY",
                    "Clé API absente. Configurez GEMINI_API_KEY dans la configuration du serveur.");
        }

        ObjectNode body = JsonCodec.MAPPER.createObjectNode();
        ObjectNode systemInstruction = body.putObject("systemInstruction");
        ArrayNode systemParts = systemInstruction.putArray("parts");
        systemParts.addObject().put("text",
                "Tu es l'assistant IA intégré à LinaChat. "
                + "Détecte automatiquement la langue de chaque échange et réponds dans la même langue "
                + "ou le même mélange naturel. Supporte parfaitement le français, l'anglais, l'arabe, "
                + "la Darija marocaine en alphabet arabe ou latin, ainsi que les fautes d'orthographe "
                + "et les messages courts. Prends en compte tout le contexte fourni, structure tes "
                + "réponses de façon naturelle et utile, donne des exemples pertinents, demande une "
                + "précision si nécessaire et n'invente jamais une information.");

        ArrayNode contents = body.putArray("contents");
        for (ChatMessage message : history) {
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(message.role()) ? "model" : "user");
            content.putArray("parts").addObject().put("text", message.content());
        }

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", 0.7);

        SendResult result = sendWithRetries(model, body);
        if (result.answer() != null) return result.answer();

        if (result.exception() != null
                && (result.exception().status() == 503
                || result.exception().status() == 500
                || result.exception().status() == 502
                || result.exception().status() == 504)
                && !fallbackModel.equals(model)) {
            System.err.println("[Gemini] Modèle principal indisponible; utilisation du fallback "
                    + fallbackModel + " (HTTP " + result.exception().status() + ")");
            SendResult fallback = sendWithRetries(fallbackModel, body);
            if (fallback.answer() != null) return fallback.answer();
            throw fallback.exception();
        }
        throw result.exception();
    }

    private SendResult sendWithRetries(String selectedModel, ObjectNode body) {
        for (int retry = 0; retry <= 2; retry++) {
            try {
                String answer = sendOnce(selectedModel, body);
                return new SendResult(answer, null);
            } catch (GeminiException e) {
                if (e.status() != 503 || retry == 2) {
                    return new SendResult(null, e);
                }
                long delayMillis = 1000L * (1L << retry);
                System.err.println("[Gemini] HTTP 503 pour " + selectedModel
                        + "; nouvelle tentative " + (retry + 1) + "/2 dans "
                        + delayMillis + " ms");
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new SendResult(null, new GeminiException(
                            "INTERRUPTED", "Requête Gemini interrompue.", 0));
                }
            } catch (HttpTimeoutException e) {
                return new SendResult(null, new GeminiException(
                        "TIMEOUT", "La requête au modèle Gemini a pris trop de temps.", 504));
            } catch (ConnectException | java.net.UnknownHostException e) {
                return new SendResult(null, new GeminiException(
                        "OFFLINE", "Connexion Internet indisponible.", 0));
            } catch (Exception e) {
                return new SendResult(null, new GeminiException(
                        "HTTP", "Erreur de communication avec Gemini: " + e.getMessage(), 0));
            }
        }
        return new SendResult(null, new GeminiException(
                "HTTP", "La requête Gemini a échoué après les tentatives.", 0));
    }

    private String sendOnce(String selectedModel, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(GEMINI_ENDPOINT + selectedModel + ":generateContent"))
                .timeout(timeout)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        java.net.http.HttpResponse<String> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw httpError(selectedModel, status, response.body());
        }

        JsonNode root = JsonCodec.MAPPER.readTree(response.body());
        for (JsonNode candidate : root.path("candidates")) {
            for (JsonNode part : candidate.path("content").path("parts")) {
                JsonNode text = part.get("text");
                if (text != null && text.isTextual() && !text.asText().isBlank()) {
                    return text.asText();
                }
            }
        }
        throw new GeminiException("INVALID_RESPONSE",
                "Réponse Gemini vide ou invalide (HTTP 200).", 200);
    }

    private GeminiException httpError(String selectedModel, int status, String responseBody) {
        String providerStatus = "";
        String providerMessage = "";
        try {
            JsonNode root = JsonCodec.MAPPER.readTree(responseBody);
            JsonNode error = root.path("error");
            providerStatus = error.path("status").asText("");
            providerMessage = error.path("message").asText("").trim();
        } catch (Exception ignored) {
            providerMessage = responseBody == null ? "" : responseBody.trim();
        }
        if (providerMessage.isBlank()) providerMessage = "Réponse Google Gemini sans message JSON.";

        System.err.println("[Gemini] " + selectedModel
                + " HTTP " + status
                + (providerStatus.isBlank() ? "" : " " + providerStatus));

        String message = "HTTP " + status
                + (providerStatus.isBlank() ? "" : " " + providerStatus)
                + ": " + providerMessage;

        if (status == 400) {
            return new GeminiException("BAD_REQUEST", message, status);
        }
        if (status == 401) {
            return new GeminiException("INVALID_KEY", message, status);
        }
        if (status == 403) {
            return new GeminiException("ACCESS_DENIED", message, status);
        }
        if (status == 429) {
            return new GeminiException("QUOTA", message, status);
        }
        if (status == 404) {
            return new GeminiException("MODEL", message, status);
        }
        if (status == 503) {
            return new GeminiException("SERVICE_UNAVAILABLE", message, status);
        }
        if (status >= 500) {
            return new GeminiException("SERVICE", message, status);
        }
        return new GeminiException("HTTP", message, status);
    }

    private static long parsePositive(String name, long fallback) {
        try {
            long value = Long.parseLong(System.getenv().getOrDefault(name, String.valueOf(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record ChatMessage(String role, String content) {}
    private record SendResult(String answer, GeminiException exception) {}

    public static final class GeminiException extends Exception {
        private final String code;
        private final int status;

        public GeminiException(String code, String message) {
            this(code, message, 0);
        }

        public GeminiException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }
}
