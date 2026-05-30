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

    private static final String MODEL_NANO_BANANA = "google/nano-banana";
    private static final long MAX_WAIT_MILLIS = 120_000L;

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
            String taskId = createNanoBananaTask(prompt, sourceImageBytes, sourceMimeType);
            String resultUrl = pollTaskResultUrl(taskId, MAX_WAIT_MILLIS);
            if (resultUrl == null || resultUrl.isBlank()) {
                throw new IllegalStateException("Не удалось получить URL результата");
            }
            return downloadBytes(resultUrl);
        } catch (AiTimeoutException timeout) {
            throw timeout;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка Nano Banana: " + e.getMessage(), e);
        }
    }

    public Integer getRemainingCredits() {
        String endpoint = "https://api.kie.ai/api/v1/chat/credit";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + config.getKieApiKey())
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 200) {
                return null;
            }
            JsonNode data = root.path("data");
            if (!data.isNumber()) {
                return null;
            }
            return data.asInt();
        } catch (Exception e) {
            log.warn("Не удалось получить баланс кредитов KIE: {}", e.getMessage());
            return null;
        }
    }

    private String createNanoBananaTask(String prompt, byte[] sourceImageBytes, String sourceMimeType)
            throws IOException, InterruptedException {
        var root = objectMapper.createObjectNode();
        root.put("model", MODEL_NANO_BANANA);

        var input = objectMapper.createObjectNode();
        input.put("prompt", prompt);
        input.put("output_format", "png");
        input.put("image_size", "1:1");

        if (sourceImageBytes != null && sourceImageBytes.length > 0) {
            String imageUrl = uploadImage(sourceImageBytes, sourceMimeType);
            var urls = objectMapper.createArrayNode();
            urls.add(imageUrl);
            input.set("image_urls", urls);
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

    private String pollTaskResultUrl(String taskId, long timeoutMillis) throws InterruptedException, IOException {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMillis;
        long delay = Math.max(500L, config.getPollDelayMillis());

        while (true) {
            long now = System.currentTimeMillis();
            long remaining = deadline - now;
            if (remaining <= 0) {
                break;
            }

            String url = config.getKieRecordInfoEndpoint() + "?taskId=" + URLEncoder.encode(taskId, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + config.getKieApiKey())
                    .GET()
                    .timeout(Duration.ofMillis(Math.min(20_000L, remaining)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
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
            }

            long sleepMs = Math.min(delay, Math.max(0L, deadline - System.currentTimeMillis()));
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
        }

        throw new AiTimeoutException("Время ожидания ответа AI превысило 120 секунд");
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
}
