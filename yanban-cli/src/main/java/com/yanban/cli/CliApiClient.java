package com.yanban.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

public class CliApiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CliConfigStore configStore;

    public CliApiClient(CliConfigStore configStore) {
        this.configStore = configStore;
    }

    public JsonNode login(String apiBaseUrl, String username, String password) {
        return sendJson("POST", apiBaseUrl + "/api/v1/auth/login", objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password), null);
    }

    public JsonNode createSession(String title) {
        return sendJson("POST", apiBaseUrl() + "/api/v1/agent/sessions", objectMapper.createObjectNode().put("title", title), accessToken());
    }

    public String chatViaSse(long sessionId, String content) {
        String requestId = "cli-" + UUID.randomUUID();
        try {
            String payload = objectMapper.createObjectNode()
                    .put("content", content)
                    .put("clientRequestId", requestId)
                    .toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            apiBaseUrl() + "/api/v1/agent/sessions/" + sessionId + "/messages/stream"))
                    .timeout(Duration.ofMinutes(20))
                    .header("Authorization", bearer())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return consumeChatStream(response);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("聊天事件流已中断", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("读取聊天事件流失败", ex);
        }
    }

    private String consumeChatStream(HttpResponse<java.io.InputStream> response) throws IOException {
        StringBuilder answer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode event = objectMapper.readTree(line.substring(5).stripLeading());
                String type = event.path("type").asText();
                if ("chunk".equals(type)) {
                    String piece = event.path("content").asText("");
                    answer.append(piece);
                    System.out.print(piece);
                } else if ("done".equals(type)) {
                    if (answer.isEmpty()) {
                        String finalAnswer = event.path("assistantContent").asText("");
                        answer.append(finalAnswer);
                        System.out.print(finalAnswer);
                    }
                    System.out.println();
                    return answer.toString();
                } else if ("error".equals(type)) {
                    System.out.println();
                    throw new IllegalStateException(event.path("error").asText("对话处理失败"));
                }
            }
        }
        throw new IllegalStateException("聊天事件流在终态前关闭");
    }

    public JsonNode getSettings() {
        return sendRequest(HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/settings"))
                .header("Authorization", bearer())
                .GET()
                .build());
    }

    public JsonNode updateSettings(JsonNode payload) {
        return sendJson("PUT", apiBaseUrl() + "/api/v1/settings", payload, accessToken());
    }

    public JsonNode listKbDocuments() {
        return sendRequest(HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/kb/documents"))
                .header("Authorization", bearer())
                .GET()
                .build());
    }

    public JsonNode simpleUpload(Path file, boolean isPublic) {
        try {
            String boundary = "----yanban" + System.currentTimeMillis();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeFormField(body, boundary, "isPublic", String.valueOf(isPublic));
            writeFileField(body, boundary, "file", file);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/kb/documents/simple-upload"))
                    .header("Authorization", bearer())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            return sendRequest(request);
        } catch (IOException ex) {
            throw new IllegalStateException("构造上传请求失败", ex);
        }
    }

    public JsonNode getPaperTask(long taskId) {
        return sendRequest(HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/paper/tasks/" + taskId))
                .header("Authorization", bearer())
                .GET()
                .build());
    }

    private void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFileField(ByteArrayOutputStream out, String boundary, String name, Path file) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode sendJson(String method, String url, JsonNode payload, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        } catch (Exception ex) {
            throw new IllegalStateException("序列化请求失败", ex);
        }
        return sendRequest(builder.build());
    }

    private JsonNode sendRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("调用后端 API 失败", ex);
        }
    }

    private String apiBaseUrl() {
        return configStore.load().getProperty("apiBaseUrl", "http://localhost:8080");
    }

    private String accessToken() {
        String token = configStore.load().getProperty("accessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("请先执行 yanban login");
        }
        return token;
    }

    private String bearer() {
        return "Bearer " + accessToken();
    }
}
