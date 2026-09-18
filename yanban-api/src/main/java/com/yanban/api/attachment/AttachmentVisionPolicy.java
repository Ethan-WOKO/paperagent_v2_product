package com.yanban.api.attachment;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Explicit deployment capability declaration; no guesses based on model names. */
@Component
public class AttachmentVisionPolicy {
    private final Set<String> models;
    public AttachmentVisionPolicy(@Value("${yanban.attachments.vision-models:}") String configured) {
        models=new HashSet<>();
        for(String item:configured.split(",")) if(!item.isBlank()) models.add(item.trim());
    }
    public void require(String provider,String model) {
        if(!models.contains(provider+":"+model)) throw new ResponseStatusException(BAD_REQUEST,
                "当前模型未配置图片理解能力，请选择已启用视觉能力的模型，或移除图片后发送");
    }
}
