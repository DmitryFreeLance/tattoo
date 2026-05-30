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

    private static final String MODEL_GPT_IMAGE_TEXT = "gpt-image-2-text-to-image";
    private static final String MODEL_GPT_IMAGE_IMAGE = "gpt-image-2-image-to-image";
    private static final String MODEL_NANO_BANANA_TEXT = "google/nano-banana";
    private static final String MODEL_NANO_BANANA_EDIT = "google/nano-banana-edit";

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
            return runGptImageTask(prompt, sourceImageBytes, sourceMimeType);
        } catch (Exception gptError) {
            log.warn("GPT Image 2 вернул ошибку, переключаюсь на Nano Banana: {}", gptError.getMessage());
            try {
                return runNanoBananaTask(prompt, sourceImageBytes, sourceMimeType);
            } catch (Exception nanoError) {
                throw new IllegalStateException(
                        "Не удалось сгенерировать изображение. GPT Image: " + safeError(gptError)
                                + " | Nano Banana: " + safeError(nanoError),
                        nanoError
                );
            }
        }
    }

    private byte[] runGptImageTask(String prompt, byte[] sourceImageBytes, String sourceMimeType) {
        try {
            String model;
            var input = objectMapper.createObjectNode();
            input.put("prompt", prompt);
            input.put("aspect_ratio", "auto");

            if (sourceImageBytes != null && sourceImageBytes.length > 0) {
                String imageUrl = uploadImage(sourceImageBytes, sourceMimeType);
                var urls = objectMapper.createArrayNode();
                urls.add(imageUrl);
                input.set("input_urls", urls);
                model = MODEL_GPT_IMAGE_IMAGE;
            } else {
                model = MODEL_GPT_IMAGE_TEXT;
            }

            return executeTaskAndDownload(model, input);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка GPT Image 2: " + e.getMessage(), e);
        }
    }

    private byte[] runNanoBananaTask(String prompt, byte[] sourceImageBytes, String sourceMimeType) {
        try {
            String model;
            var input = objectMapper.createObjectNode();
            input.put("prompt", prompt);
            input.put("output_format", "png");
            input.put("aspect_ratio", "auto");

            if (sourceImageBytes != null && sourceImageBytes.length > 0) {
                String imageUrl = uploadImage(sourceImageBytes, sourceMimeType);
                var urls = objectMapper.createArrayNode();
                urls.add(imageUrl);
                input.set("image_urls", urls);
                model = MODEL_NANO_BANANA_EDIT;
            } else {
                model = MODEL_NANO_BANANA_TEXT;
            }

            return executeTaskAndDownload(model, input);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка Nano Banana: " + e.getMessage(), e);
        }
    }

    private byte[] executeTaskAndDownload(String model, JsonNode input) throws IOException, InterruptedException {
        String taskId = createTask(model, input);
        String resultUrl = pollTaskResultUrl(taskId);
        if (resultUrl == null || resultUrl.isBlank()) {
            throw new IllegalStateException("Не удалось получить URL результата");
        }
        return downloadBytes(resultUrl);
    }

    private String createTask(String model, JsonNode input) throws IOException, InterruptedException {
        var root = objectMapper.createObjectNode();
        root.put("model", model);
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

    private static String safeError(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return "unknown";
        }
        return exception.getMessage().replace("\n", " ").trim();
    }
}
