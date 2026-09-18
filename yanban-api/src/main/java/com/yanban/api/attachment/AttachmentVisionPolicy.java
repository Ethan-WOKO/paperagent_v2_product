package com.yanban.api.attachment;

import java.util.*;
import com.yanban.api.settings.UserModelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Explicit per-user capability; legacy deployment declarations remain a fallback. */
@Component
public class AttachmentVisionPolicy {
    private final Set<String> models;
    private final UserModelRepository repository;

    @Autowired
    public AttachmentVisionPolicy(@Value("${yanban.attachments.vision-models:}") String configured,
                                  UserModelRepository repository) {
        this.repository = repository;
        models = new HashSet<>();
        for (String item : configured.split(",")) if (!item.isBlank()) models.add(item.trim());
    }

    public AttachmentVisionPolicy(String configured) { this(configured, null); }

    public void require(Long userId, String provider, String model) {
        if (userId != null && repository != null) {
            var configured = repository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                    .filter(item -> Objects.equals(provider, item.getProviderKey())
                            && Objects.equals(model, item.getModelName()))
                    .findFirst();
            if (configured.isPresent() && configured.get().getSupportsVision() != null) {
                if (Boolean.TRUE.equals(configured.get().getSupportsVision())) return;
                throw new ResponseStatusException(BAD_REQUEST,
                        "当前模型未启用图片输入，请在模型设置中开启“支持图片输入”，或选择其他视觉模型");
            }
        }
        if (!models.contains(provider + ":" + model)) throw new ResponseStatusException(BAD_REQUEST,
                "当前模型尚未配置图片理解能力，请编辑模型并开启“支持图片输入”；仅对实际支持图片的接口启用");
    }
}
