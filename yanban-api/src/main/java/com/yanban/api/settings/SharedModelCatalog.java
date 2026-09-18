package com.yanban.api.settings;

import java.net.URI;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SharedModelCatalog {
    private final JdbcTemplate jdbc;
    private final SettingsCryptoService crypto;
    private final ModelDiscoveryService discovery;
    public SharedModelCatalog(JdbcTemplate jdbc, SettingsCryptoService crypto, ModelDiscoveryService discovery) {
        this.jdbc=jdbc; this.crypto=crypto; this.discovery=discovery;
    }
    public record ProviderInput(@NotBlank @Size(max=128) String name,
            @NotBlank @Size(max=512) String chatUrl, @Size(max=512) String modelsUrl,
            @Size(max=4096) String apiKey, boolean enabled) {}
    public record ProviderView(long id, String name, String chatUrl, String modelsUrl, boolean apiKeyConfigured, boolean enabled) {}
    public record ModelInput(@NotBlank @Size(max=128) String modelName, boolean approved, boolean supportsVision) {}
    public record ModelView(long id, long providerId, String modelName, boolean approved, boolean supportsVision, boolean available) {}
    private record Provider(long id, String name, String chatUrl, String modelsUrl, String secret, boolean enabled) {}
    private Provider provider(long id, boolean lock) {
        return jdbc.query("SELECT * FROM shared_model_providers WHERE id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Provider(r.getLong("id"),r.getString("name"),r.getString("chat_url"),r.getString("models_url"),r.getString("api_key_encrypted"),r.getBoolean("enabled")),id)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"模型厂商不存在"));
    }
    public List<ProviderView> providers() {
        return jdbc.query("SELECT * FROM shared_model_providers ORDER BY id",(r,n)->new ProviderView(r.getLong("id"),r.getString("name"),r.getString("chat_url"),r.getString("models_url"),StringUtils.hasText(r.getString("api_key_encrypted")),r.getBoolean("enabled")));
    }
    @Transactional
    public long saveProvider(Long id, ProviderInput input) {
        String chat = checkedUrl(input.chatUrl());
        if (!chat.endsWith("/chat/completions")) chat=chat.replaceAll("/+$", "")+"/chat/completions";
        String models = StringUtils.hasText(input.modelsUrl()) ? checkedUrl(input.modelsUrl()) : null;
        Provider old = id==null ? null : provider(id,true);
        String secret = StringUtils.hasText(input.apiKey()) ? crypto.encrypt(input.apiKey().trim()) : old==null ? null : old.secret();
        if (!StringUtils.hasText(secret)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写厂商 API Key");
        if(id!=null) {
            jdbc.update("UPDATE shared_model_providers SET name=?,chat_url=?,models_url=?,api_key_encrypted=?,enabled=? WHERE id=?",input.name().trim(),chat,models,secret,input.enabled(),id);
            return id;
        }
        var key = new GeneratedKeyHolder();
        final String url=chat;
        jdbc.update(c->{ var ps=c.prepareStatement("INSERT INTO shared_model_providers(name,chat_url,models_url,api_key_encrypted,enabled) VALUES(?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,input.name().trim()); ps.setString(2,url); ps.setString(3,models); ps.setString(4,secret); ps.setBoolean(5,input.enabled()); return ps; },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    static String checkedUrl(String value) {
        try {
            URI uri=URI.create(value.trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null) throw new IllegalArgumentException();
            return uri.toString().replaceAll("/+$", "");
        } catch(Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入有效的 HTTP(S) 接口地址（不含密钥、查询参数或片段）"); }
    }
    public List<ModelView> models(long providerId) {
        provider(providerId,false);
        return jdbc.query("SELECT * FROM shared_models WHERE provider_id=? ORDER BY model_name",(r,n)->new ModelView(r.getLong("id"),r.getLong("provider_id"),r.getString("model_name"),r.getBoolean("approved"),r.getBoolean("supports_vision"),r.getBoolean("available")),providerId);
    }
    @Transactional
    public void saveModel(long providerId, ModelInput input) {
        provider(providerId,true);
        String name=input.modelName().trim();
        int changed=jdbc.update("UPDATE shared_models SET approved=?,supports_vision=? WHERE provider_id=? AND model_name=?",input.approved(),input.supportsVision(),providerId,name);
        if(changed==0) jdbc.update("INSERT INTO shared_models(provider_id,model_name,approved,supports_vision,available,manual) VALUES(?,?,?,?,TRUE,TRUE)",providerId,name,input.approved(),input.supportsVision());
    }
    @Transactional
    public List<ModelView> sync(long providerId) {
        Provider p=provider(providerId,true);
        if(!StringUtils.hasText(p.modelsUrl())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"此厂商尚未配置模型列表接口，请填写接口或手动添加模型 ID");
        List<String> names=discovery.discoverModels(p.modelsUrl(),crypto.decrypt(p.secret()));
        // Network/parse failures occur before touching the last successful catalog.
        jdbc.update("UPDATE shared_models SET available=FALSE WHERE provider_id=? AND manual=FALSE",providerId);
        for(String name:names) {
            int changed=jdbc.update("UPDATE shared_models SET available=TRUE WHERE provider_id=? AND model_name=?",providerId,name);
            if(changed==0) jdbc.update("INSERT INTO shared_models(provider_id,model_name,approved,supports_vision,available,manual) VALUES(?,?,FALSE,FALSE,TRUE,FALSE)",providerId,name);
        }
        return models(providerId);
    }
    public List<UserModelResponse> availableModels() {
        return jdbc.query("SELECT m.*,p.name FROM shared_models m JOIN shared_model_providers p ON p.id=m.provider_id WHERE p.enabled=TRUE AND m.approved=TRUE AND m.available=TRUE ORDER BY p.id,m.model_name",
                (r,n)->new UserModelResponse(-r.getLong("id"),"shared-"+r.getLong("id"),r.getString("name"),r.getString("model_name"),null,true,true,0,null,null,r.getBoolean("supports_vision")));
    }
    public UserSettingsService.ModelEndpoint resolve(String providerKey, String model) {
        long id=parseId(providerKey);
        var rows=jdbc.query("SELECT m.*,p.name,p.chat_url,p.api_key_encrypted FROM shared_models m JOIN shared_model_providers p ON p.id=m.provider_id WHERE m.id=? AND p.enabled=TRUE AND m.approved=TRUE AND m.available=TRUE",
                (r,n)->new UserSettingsService.ModelEndpoint(providerKey,r.getString("model_name"),r.getString("chat_url"),crypto.decrypt(r.getString("api_key_encrypted")),"shared",r.getString("name")),id);
        if(rows.isEmpty() || (StringUtils.hasText(model) && !rows.get(0).modelName().equals(model.trim())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该共享模型未获批准、已停用或不可用，请重新选择模型");
        return rows.get(0);
    }
    public boolean supportsVision(String providerKey,String model) {
        resolve(providerKey,model);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT supports_vision FROM shared_models WHERE id=?",Boolean.class,parseId(providerKey)));
    }
    private long parseId(String key) {
        try { return Long.parseLong(key.substring("shared-".length())); }
        catch(RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效共享模型标识"); }
    }
}
