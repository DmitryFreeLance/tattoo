package com.tattoo.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    private static final String MODEL_GPT_IMAGE_2_TEXT_TO_IMAGE = "gpt-image-2-text-to-image";
    private static final String MODEL_GPT_IMAGE_2_IMAGE_TO_IMAGE = "gpt-image-2-image-to-image";
    private static final String MODEL_NANO_BANANA = "google/nano-banana";
    private static final long MAX_WAIT_MILLIS = 120_000L;
    private static final double ASPECT_EPSILON = 0.002d;

    private static final AspectPreset[] ASPECT_PRESETS = new AspectPreset[] {
            new AspectPreset("1:1", 1, 1),
            new AspectPreset("3:2", 3, 2),
            new AspectPreset("2:3", 2, 3),
            new AspectPreset("4:3", 4, 3),
            new AspectPreset("3:4", 3, 4),
            new AspectPreset("5:4", 5, 4),
            new AspectPreset("4:5", 4, 5),
            new AspectPreset("16:9", 16, 9),
            new AspectPreset("9:16", 9, 16),
            new AspectPreset("21:9", 21, 9),
            new AspectPreset("9:21", 9, 21),
            new AspectPreset("2:1", 2, 1),
            new AspectPreset("1:2", 1, 2)
    };

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
        boolean hasSourceImage = sourceImageBytes != null && sourceImageBytes.length > 0;
        String primaryModel = hasSourceImage
                ? MODEL_GPT_IMAGE_2_IMAGE_TO_IMAGE
                : MODEL_GPT_IMAGE_2_TEXT_TO_IMAGE;
        log.info("KIE generation started. primaryModel={}, hasSourceImage={}", primaryModel, hasSourceImage);

        int[] sourceDimensions = hasSourceImage ? detectImageDimensions(sourceImageBytes) : null;
        String sourceAspect = detectAspectRatioPreset(sourceDimensions);

        try {
            return generateImageWithModel(primaryModel, prompt, sourceImageBytes, sourceMimeType, sourceDimensions, sourceAspect);
        } catch (AiTimeoutException primaryTimeout) {
            log.warn("Primary model {} timed out after 120s. Switching to fallback {}.", primaryModel, MODEL_NANO_BANANA);
            try {
                return generateImageWithModel(MODEL_NANO_BANANA, prompt, sourceImageBytes, sourceMimeType, sourceDimensions, sourceAspect);
            } catch (AiTimeoutException fallbackTimeout) {
                throw new AiTimeoutException("Обе модели не ответили за 120 секунд (основная и fallback).");
            } catch (Exception fallbackError) {
                throw new IllegalStateException("Ошибка fallback Nano Banana: " + fallbackError.getMessage(), fallbackError);
            }
        } catch (Exception primaryError) {
            throw new IllegalStateException("Ошибка GPT Image 2: " + primaryError.getMessage(), primaryError);
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

    private byte[] generateImageWithModel(
            String model,
            String prompt,
            byte[] sourceImageBytes,
            String sourceMimeType,
            int[] sourceDimensions,
            String sourceAspect
    )
            throws IOException, InterruptedException {
        String taskId = createTask(model, prompt, sourceImageBytes, sourceMimeType, sourceAspect);
        String resultUrl = pollTaskResultUrl(taskId, MAX_WAIT_MILLIS);
        if (resultUrl == null || resultUrl.isBlank()) {
            throw new IllegalStateException("Не удалось получить URL результата");
        }
        byte[] raw = downloadBytes(resultUrl);
        return forceAspectToSource(raw, sourceDimensions);
    }

    private String createTask(
            String model,
            String prompt,
            byte[] sourceImageBytes,
            String sourceMimeType,
            String sourceAspect
    )
            throws IOException, InterruptedException {
        var root = objectMapper.createObjectNode();
        root.put("model", model);

        var input = objectMapper.createObjectNode();
        input.put("prompt", prompt == null ? "" : prompt.trim());

        if (isGptImageModel(model)) {
            input.put("aspect_ratio", sourceAspect == null ? "auto" : sourceAspect);
        } else if (MODEL_NANO_BANANA.equals(model)) {
            input.put("output_format", "png");
            input.put("aspect_ratio", sourceAspect == null ? "1:1" : sourceAspect);
        }

        if (sourceImageBytes != null && sourceImageBytes.length > 0) {
            String imageUrl = uploadImage(sourceImageBytes, sourceMimeType);
            var urls = objectMapper.createArrayNode();
            urls.add(imageUrl);
            if (MODEL_GPT_IMAGE_2_IMAGE_TO_IMAGE.equals(model)) {
                input.set("input_urls", urls);
            } else {
                input.set("image_urls", urls);
            }
        }

        log.debug("KIE createTask payload model={}, hasPrompt={}, hasImage={}",
                model,
                prompt != null && !prompt.isBlank(),
                sourceImageBytes != null && sourceImageBytes.length > 0);

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

    private boolean isGptImageModel(String model) {
        return MODEL_GPT_IMAGE_2_TEXT_TO_IMAGE.equals(model) || MODEL_GPT_IMAGE_2_IMAGE_TO_IMAGE.equals(model);
    }

    private int[] detectImageDimensions(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            return new int[] {image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            log.debug("Не удалось определить размеры исходного изображения: {}", e.getMessage());
            return null;
        }
    }

    private String detectAspectRatioPreset(int[] dimensions) {
        if (dimensions == null || dimensions.length < 2) {
            return null;
        }
        int width = dimensions[0];
        int height = dimensions[1];
        if (width <= 0 || height <= 0) {
            return null;
        }

        double ratio = (double) width / (double) height;
        AspectPreset best = null;
        double bestDelta = Double.MAX_VALUE;
        for (AspectPreset preset : ASPECT_PRESETS) {
            double delta = Math.abs(Math.log(ratio / preset.ratio()));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = preset;
            }
        }
        return best == null ? null : best.token();
    }

    private byte[] forceAspectToSource(byte[] generatedBytes, int[] sourceDimensions) {
        if (generatedBytes == null || generatedBytes.length == 0 || sourceDimensions == null || sourceDimensions.length < 2) {
            return generatedBytes;
        }

        int sourceW = sourceDimensions[0];
        int sourceH = sourceDimensions[1];
        if (sourceW <= 0 || sourceH <= 0) {
            return generatedBytes;
        }

        try {
            BufferedImage generated = ImageIO.read(new ByteArrayInputStream(generatedBytes));
            if (generated == null) {
                return generatedBytes;
            }

            int gw = generated.getWidth();
            int gh = generated.getHeight();
            if (gw <= 0 || gh <= 0) {
                return generatedBytes;
            }

            double targetRatio = (double) sourceW / (double) sourceH;
            double currentRatio = (double) gw / (double) gh;
            if (Math.abs(currentRatio - targetRatio) <= ASPECT_EPSILON) {
                return generatedBytes;
            }

            int cropW = gw;
            int cropH = gh;
            if (currentRatio > targetRatio) {
                cropW = Math.max(1, (int) Math.round(gh * targetRatio));
            } else {
                cropH = Math.max(1, (int) Math.round(gw / targetRatio));
            }
            cropW = Math.min(cropW, gw);
            cropH = Math.min(cropH, gh);

            int x = Math.max(0, (gw - cropW) / 2);
            int y = Math.max(0, (gh - cropH) / 2);

            BufferedImage cropped = generated.getSubimage(x, y, cropW, cropH);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(cropped, "png", out)) {
                return generatedBytes;
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.debug("Не удалось привести аспект результата к исходному: {}", e.getMessage());
            return generatedBytes;
        }
    }

    private record AspectPreset(String token, int width, int height) {
        private double ratio() {
            return (double) width / (double) height;
        }
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
