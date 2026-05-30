package com.tattoo.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KieAiClient {
    private static final Logger log = LoggerFactory.getLogger(KieAiClient.class);
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\"]+");

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public KieAiClient(AppConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public byte[] generateImage(String prompt, byte[] sourceImageBytes, String sourceMimeType) {
        try {
            byte[] geminiImage = tryGemini(prompt, sourceImageBytes, sourceMimeType);
            if (geminiImage != null && geminiImage.length > 0) {
                return geminiImage;
            }
            throw new IllegalStateException("Gemini вернул пустой результат");
        } catch (Exception e) {
            log.warn("Gemini не вернул изображение, переключаюсь на GPT Image 2: {}", e.getMessage());
            return runGptImageTask(prompt, sourceImageBytes, sourceMimeType);
        }
    }

    private byte[] tryGemini(String prompt, byte[] sourceImageBytes, String sourceMimeType) {
        try {
            String uploadedUrl = null;
            if (sourceImageBytes != null && sourceImageBytes.length > 0) {
                uploadedUrl = uploadImage(sourceImageBytes, sourceMimeType);
            }

            JsonNode payload = buildGeminiPayload(prompt, uploadedUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getKieGeminiEndpoint()))
                    .header("Authorization", "Bearer " + config.getKieApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String imageUrl = extractImageUrlFromGemini(json);
            if (imageUrl == null || imageUrl.isBlank()) {
                throw new IllegalStateException("Gemini 3 Flash не вернул image URL");
            }
            return downloadBytes(imageUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка Gemini: " + e.getMessage(), e);
        }
    }

    private JsonNode buildGeminiPayload(String prompt, String uploadedUrl) {
        JsonNode root = objectMapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("model", "gemini-3-flash");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("stream", false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("temperature", 0.2);

        var messages = objectMapper.createArrayNode();
        var userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");

        var content = objectMapper.createArrayNode();
        var textPart = objectMapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);

        if (uploadedUrl != null && !uploadedUrl.isBlank()) {
            var imagePart = objectMapper.createObjectNode();
            imagePart.put("type", "image_url");
            var imageNode = objectMapper.createObjectNode();
            imageNode.put("url", uploadedUrl);
            imagePart.set("image_url", imageNode);
            content.add(imagePart);
        }

        userMessage.set("content", content);
        messages.add(userMessage);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("messages", messages);
        return root;
    }

    private String extractImageUrlFromGemini(JsonNode json) {
        if (json == null) {
            return null;
        }

        JsonNode choices = json.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode first = choices.get(0);
            JsonNode message = first.path("message");
            JsonNode content = message.get("content");

            if (content != null) {
                if (content.isTextual()) {
                    String text = content.asText();
                    String maybeUrl = extractFirstUrl(text);
                    if (looksLikeImageUrl(maybeUrl)) {
                        return maybeUrl;
                    }
                }

                if (content.isArray()) {
                    for (JsonNode part : content) {
                        if (part.has("image_url")) {
                            JsonNode imageUrlNode = part.path("image_url").path("url");
                            if (!imageUrlNode.asText().isBlank()) {
                                return imageUrlNode.asText();
                            }
                        }
                        if (part.has("url") && looksLikeImageUrl(part.path("url").asText())) {
                            return part.path("url").asText();
                        }
                    }
                }
            }
        }
        return null;
    }

    private byte[] runGptImageTask(String prompt, byte[] sourceImageBytes, String sourceMimeType) {
        try {
            String imageUrl = null;
            if (sourceImageBytes != null && sourceImageBytes.length > 0) {
                imageUrl = uploadImage(sourceImageBytes, sourceMimeType);
            }

            String taskId = createTask(prompt, imageUrl);
            String resultUrl = pollTaskResultUrl(taskId);
            if (resultUrl == null) {
                throw new IllegalStateException("Не удалось получить URL сгенерированного изображения");
            }
            return downloadBytes(resultUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка GPT Image fallback: " + e.getMessage(), e);
        }
    }

    private String createTask(String prompt, String imageUrl) throws IOException, InterruptedException {
        String model = imageUrl == null ? "gpt-image-2-text-to-image" : "gpt-image-2-image-to-image";

        var root = objectMapper.createObjectNode();
        root.put("model", model);
        var input = objectMapper.createObjectNode();
        input.put("prompt", prompt);
        input.put("aspect_ratio", "auto");
        if (imageUrl != null) {
            var arr = objectMapper.createArrayNode();
            arr.add(imageUrl);
            input.set("input_urls", arr);
        }
        root.set("input", input);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getKieCreateTaskEndpoint()))
                .header("Authorization", "Bearer " + config.getKieApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Ошибка createTask HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String taskId = json.path("data").path("taskId").asText();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("taskId не пришел: " + response.body());
        }
        return taskId;
    }

    private String pollTaskResultUrl(String taskId) throws InterruptedException, IOException {
        int attempts = config.getPollAttempts();
        long delay = config.getPollDelayMillis();

        for (int i = 0; i < attempts; i++) {
            String url = config.getKieRecordInfoEndpoint() + "?taskId=" + URLEncoder.encode(taskId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + config.getKieApiKey())
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Thread.sleep(delay);
                continue;
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode data = json.path("data");
            String state = data.path("state").asText("");

            if ("success".equalsIgnoreCase(state)) {
                String resultJson = data.path("resultJson").asText("");
                String resultUrl = extractResultUrl(resultJson);
                if (resultUrl != null) {
                    return resultUrl;
                }
                throw new IllegalStateException("Задача завершилась success, но URL результата отсутствует");
            }

            if ("fail".equalsIgnoreCase(state)) {
                String failMsg = data.path("failMsg").asText("неизвестная ошибка");
                throw new IllegalStateException("KIE задача завершилась с ошибкой: " + failMsg);
            }

            Thread.sleep(delay);
        }

        throw new IllegalStateException("Превышено время ожидания генерации");
    }

    private String extractResultUrl(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(resultJson);
            List<String> candidates = new ArrayList<>();

            JsonNode resultUrls = parsed.path("resultUrls");
            if (resultUrls.isArray()) {
                for (JsonNode node : resultUrls) {
                    candidates.add(node.asText());
                }
            }

            JsonNode images = parsed.path("images");
            if (images.isArray()) {
                for (JsonNode node : images) {
                    if (node.has("url")) {
                        candidates.add(node.path("url").asText());
                    }
                }
            }

            for (String candidate : candidates) {
                if (candidate != null && candidate.startsWith("http")) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            String fallback = extractFirstUrl(resultJson);
            if (fallback != null && fallback.startsWith("http")) {
                return fallback;
            }
        }
        return null;
    }

    private String uploadImage(byte[] imageBytes, String mimeType) throws IOException, InterruptedException {
        String effectiveMime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + effectiveMime + ";base64," + base64;

        var payload = objectMapper.createObjectNode();
        payload.put("base64Data", dataUrl);
        payload.put("uploadPath", "images/tattoo-bot");
        payload.put("fileName", "tg_" + System.currentTimeMillis() + ".jpg");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getKieFileUploadEndpoint()))
                .header("Authorization", "Bearer " + config.getKieApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .timeout(Duration.ofSeconds(40))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Ошибка upload file HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String downloadUrl = json.path("data").path("downloadUrl").asText();
        if (downloadUrl != null && !downloadUrl.isBlank()) {
            return downloadUrl;
        }
        String fileUrl = json.path("data").path("fileUrl").asText();
        if (fileUrl != null && !fileUrl.isBlank()) {
            return fileUrl;
        }

        throw new IllegalStateException("Upload завершился без URL: " + response.body());
    }

    private byte[] downloadBytes(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ошибка загрузки результата HTTP " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось скачать изображение: " + e.getMessage(), e);
        }
    }

    private static String extractFirstUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static boolean looksLikeImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains(".png")
                || lower.contains(".jpg")
                || lower.contains(".jpeg")
                || lower.contains(".webp")
                || lower.contains("download")
                || lower.contains("image");
    }
}
