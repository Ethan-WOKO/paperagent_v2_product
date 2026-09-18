package com.yanban.api.attachment;

import com.yanban.api.settings.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentVisionPolicyTest {
    @Test void capabilityIsScopedToOwnerProviderAndModelAndCanBeDisabled() {
        var repository = mock(UserModelRepository.class);
        var model = new UserModel(1L, "custom-1", "Qwen", "qwen3.8-max", null, null, false, 1);
        model.setSupportsVision(true);
        when(repository.findByUserIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(model));
        when(repository.findByUserIdOrderBySortOrderAscIdAsc(2L)).thenReturn(List.of());
        var policy = new AttachmentVisionPolicy("", repository);
        assertThatCode(() -> policy.require(1L, "custom-1", "qwen3.8-max")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.require(2L, "custom-1", "qwen3.8-max")).hasMessageContaining("尚未配置");
        assertThatThrownBy(() -> policy.require(1L, "custom-2", "qwen3.8-max")).hasMessageContaining("尚未配置");
        assertThatThrownBy(() -> policy.require(1L, "custom-1", "text-model")).hasMessageContaining("尚未配置");
        model.setSupportsVision(false);
        assertThatThrownBy(() -> policy.require(1L, "custom-1", "qwen3.8-max")).hasMessageContaining("未启用");
    }

    @Test void legacyWhitelistWorksOnlyWhenCapabilityIsUnspecified() {
        var repository = mock(UserModelRepository.class);
        var model = new UserModel(1L, "custom-1", "Qwen", "qwen3.8-max", null, null, false, 1);
        when(repository.findByUserIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(model));
        var policy = new AttachmentVisionPolicy("custom-1:qwen3.8-max", repository);
        assertThatCode(() -> policy.require(1L, "custom-1", "qwen3.8-max")).doesNotThrowAnyException();
        model.setSupportsVision(false);
        assertThatThrownBy(() -> policy.require(1L, "custom-1", "qwen3.8-max")).hasMessageContaining("未启用");
    }
}
