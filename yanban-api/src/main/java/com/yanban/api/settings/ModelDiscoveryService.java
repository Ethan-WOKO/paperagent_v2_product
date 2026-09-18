package com.yanban.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.DeepSeekProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelDiscoveryService {

    private final DeepSeekProperties deepSeekProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ModelDiscoveryService(DeepSeekProperties deepSeekProperties,
                                 WebClient.Builder webClientBuilder,
                                 ObjectMapper objectMapper) {
        this.deepSeekProperties = deepSeekProperties;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<String> discoverDeepSeekModels(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DeepSeek API key is required before refreshing models.");
        }
        if (!StringUtils.hasText(deepSeekProperties.getModelsUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DeepSeek modelsUrl is not configured.");
        }

        try {
            String body = webClient.get()
                    .uri(deepSeekProperties.getModelsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(deepSeekProperties.getTimeout());
            List<String> modelIds = parseModelIds(body);
            if (modelIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek models API returned no model ids.");
            }
            return modelIds;
        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DeepSeek models API failed: HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek models API request failed.", ex);
        }
    }

    public List<String> discoverModels(String url, String apiKey) {
        if (!StringUtils.hasText(apiKey)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先配置 API Key");
        try {
            String body = webClient.get().uri(SharedModelCatalog.checkedUrl(url))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(20));
            List<String> ids = parseModelIds(body);
            if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型列表为空或格式不兼容，已保留原列表；可由管理员手动添加模型");
            if(ids.size()>2000 || ids.stream().anyMatch(id -> id.length()>128))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "厂商模型列表超出支持范围，已保留原列表");
            return ids;
        } catch(WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型列表同步失败 HTTP " + ex.getStatusCode().value() + "，已保留原列表；请检查列表接口与密钥权限，或手动添加模型");
        } catch(ResponseStatusException ex) { throw ex; }
        catch(RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"模型列表请求失败，已保留原列表"); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.yanban.core.model.GlmProperties glmProperties;
    public List<String> discoverGlmModels(String apiKey) {
        String url = glmProperties.getApiUrl().replaceAll("/chat/completions/?$", "/models");
        return discoverModels(url, StringUtils.hasText(apiKey) ? apiKey : glmProperties.getApiKey());
    }

    private List<String> parseModelIds(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (StringUtils.hasText(id)) {
                    ids.add(id.trim());
                }
            }
            return new ArrayList<>(ids);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to parse DeepSeek models response.", ex);
        }
    }
}
