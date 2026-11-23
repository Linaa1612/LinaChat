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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final List<String> fallbackModels;
    private final Duration timeout;
    private final Map<String, Instant> quotaExhaustedUntil = new ConcurrentHashMap<>();
    private final Map<String, Boolean> modelAvailability = new ConcurrentHashMap<>();
    private static final Set<String> FREE_TIER_MODELS = Set.of(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite",
            "gemini-3-flash-preview",
            "gemini-flash-lite-latest",
            "gemini-flash-latest");

    public GeminiClient() {
        String configuredKey = System.getenv("GEMINI_API_KEY");
        this.apiKey = configuredKey == null ? null : configuredKey.trim();
        String configuredModel = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.8-flash").trim();
        this.model = configuredModel.isBlank() ? "gemini-3.8-flash" : configuredModel;
        String configuredFallbacks = System.getenv().getOrDefault(
                "GEMINI_FALLBACK_MODELS",
                "gemini-3.5-flash-lite,gemini-3.5-flash,gemini-3.1-flash-lite,gemini-3-flash-preview");
        LinkedHashSet<String> alternatives = new LinkedHashSet<>();
        for (String candidate : configuredFallbacks.split(",")) {
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()
                    && FREE_TIER_MODELS.contains(normalized)
                    && !normalized.equals(this.model)) {
                alternatives.add(normalized);
            }
        }
        this.fallbackModels = new ArrayList<>(alternatives);
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

        GeminiException primaryQuotaBlock = quotaBlock(model);
        if (primaryQuotaBlock != null) {
            System.err.println("[Gemini] Blocage local du quota pour " + model);
            return tryFallbackModels(body, primaryQuotaBlock);
        }

        SendResult result = sendWithRetries(model, body);
        if (result.answer() != null) return result.answer();

        if (result.exception() != null
                && (result.exception().status() == 429
                || result.exception().status() == 503
                || result.exception().status() == 500
                || result.exception().status() == 502
                || result.exception().status() == 504)) {
            if (result.exception().status() == 429) {
                markQuotaExhausted(model, result.exception());
            }
            System.err.println("[Gemini] Modèle principal indisponible; recherche d'un modèle Free Tier disponible "
                    + "(HTTP " + result.exception().status() + ")");
            return tryFallbackModels(body, result.exception());
        }
        throw result.exception();
    }

    private String tryFallbackModelsResult(ObjectNode body, GeminiException primaryError) throws Exception {
        GeminiException lastError = primaryError;
        for (String candidate : fallbackModels) {
            GeminiException blocked = quotaBlock(candidate);
            if (blocked != null) {
                System.err.println("[Gemini] " + candidate + " ignoré: quota épuisé localement");
                lastError = blocked;
                continue;
            }
            if (!isAvailable(candidate)) {
                System.err.println("[Gemini] " + candidate + " indisponible ou refusé par l'API");
                continue;
            }
            SendResult fallback = sendWithRetries(candidate, body);
            if (fallback.answer() != null) {
                return fallback.answer();
            }
            lastError = fallback.exception();
            if (lastError != null && lastError.status() == 429) {
                markQuotaExhausted(candidate, lastError);
            }
        }
        throw lastError;
    }

    private String tryFallbackModels(ObjectNode body, GeminiException primaryError) throws Exception {
        return tryFallbackModelsResult(body, primaryError);
    }

    private boolean isAvailable(String selectedModel) {
        Boolean cached = modelAvailability.get(selectedModel);
        if (cached != null) return cached;
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(GEMINI_ENDPOINT + selectedModel))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-goog-api-key", apiKey)
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response =
                    http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            boolean available = response.statusCode() == 200;
            modelAvailability.put(selectedModel, available);
            if (!available) {
                System.err.println("[Gemini] Modèle alternatif " + selectedModel
                        + " non disponible via l'API (HTTP " + response.statusCode() + ")");
            }
            return available;
        } catch (Exception e) {
            System.err.println("[Gemini] Vérification du modèle " + selectedModel + " impossible");
            modelAvailability.put(selectedModel, false);
            return false;
        }
    }

    private GeminiException quotaBlock(String selectedModel) {
        Instant until = quotaExhaustedUntil.get(selectedModel);
        if (until == null) return null;
        Instant now = Instant.now();
        if (until.isAfter(now)) {
            long seconds = Duration.between(now, until).toSeconds();
            String reset = formatDuration(Math.max(1, seconds));
            return new GeminiException("QUOTA",
                    "HTTP 429 RESOURCE_EXHAUSTED: quota épuisé pour " + selectedModel
                            + ". Réinitialisation estimée dans " + reset + ".",
                    429, Duration.ofSeconds(seconds).toMillis());
        }
        quotaExhaustedUntil.remove(selectedModel);
        return null;
    }

    private void markQuotaExhausted(String selectedModel, GeminiException exception) {
        long retryMillis = exception.retryAfterMillis();
        if (retryMillis <= 0) retryMillis = 24L * 60L * 60L * 1000L;
        quotaExhaustedUntil.put(selectedModel, Instant.now().plusMillis(retryMillis));
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
        long retryAfterMillis = 0;
        try {
            JsonNode root = JsonCodec.MAPPER.readTree(responseBody);
            JsonNode error = root.path("error");
            providerStatus = error.path("status").asText("");
            providerMessage = error.path("message").asText("").trim();
            for (JsonNode detail : error.path("details")) {
                JsonNode retryDelay = detail.get("retryDelay");
                if (retryDelay != null && retryDelay.isTextual()) {
                    retryAfterMillis = parseRetryDelay(retryDelay.asText());
                }
            }
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
            if (retryAfterMillis > 0) {
                message += " Réinitialisation estimée dans "
                        + formatDuration(retryAfterMillis / 1000);
            }
            return new GeminiException("QUOTA", message, status, retryAfterMillis);
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

    private static long parseRetryDelay(String delay) {
        if (delay == null) return 0;
        String normalized = delay.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("s")) return 0;
        try {
            double seconds = Double.parseDouble(normalized.substring(0, normalized.length() - 1));
            return Math.max(1, Math.round(seconds * 1000));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) return hours + " h " + minutes + " min";
        if (minutes > 0) return minutes + " min " + remainingSeconds + " s";
        return remainingSeconds + " s";
    }

    public record ChatMessage(String role, String content) {}
    private record SendResult(String answer, GeminiException exception) {}

    public static final class GeminiException extends Exception {
        private final String code;
        private final int status;
        private final long retryAfterMillis;

        public GeminiException(String code, String message) {
            this(code, message, 0, 0);
        }

        public GeminiException(String code, String message, int status) {
            this(code, message, status, 0);
        }

        public GeminiException(String code, String message, int status, long retryAfterMillis) {
            super(message);
            this.code = code;
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }
}
