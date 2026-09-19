package com.yanban.api.agent.reactplan.gateway;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Convert provider failures to fixed safe diagnostics. Never expose raw response bodies. */
final class ModelFailureDiagnostic {
    static EngineGatewayException from(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (int n = 0; failure != null && n < 8; n++, failure = failure.getCause()) {
            text.append(' ').append(failure.getMessage());
        }
        String value = text.toString().toLowerCase(Locale.ROOT);
        var status = Pattern.compile("http[ :]*(\\d{3})").matcher(value);
        String http = status.find() ? status.group(1) : "unknown";
        String code = "MODEL_PROVIDER_FAILED";
        String message = "模型供应商调用失败，请检查模型配置或供应商状态。";
        if (value.contains("insufficient balance") || value.contains("insufficient_quota")
                || value.contains("余额不足") || value.contains("credit balance is too low")
                || value.contains("insufficient credit")) {
            code = "MODEL_PROVIDER_QUOTA_EXHAUSTED";
            message = "模型供应商报告余额或配额不足，请检查该账户余额与额度。";
        } else if (http.equals("401") || http.equals("403")) {
            code = "MODEL_PROVIDER_AUTH_FAILED";
            message = "模型供应商拒绝认证或授权，请检查 API Key 和模型访问权限。";
        } else if (http.equals("429")) {
            code = "MODEL_PROVIDER_RATE_LIMITED";
            message = "模型供应商返回限流或配额限制；仅凭 HTTP 429 无法判断余额不足。";
        } else if (value.contains("model_not_found") || value.contains("model not found")) {
            code = "MODEL_PROVIDER_MODEL_NOT_FOUND";
            message = "模型供应商报告模型不存在或不可用，请检查模型名称。";
        } else if (value.contains("timeout") || value.contains("timed out")) {
            code = "MODEL_PROVIDER_TIMEOUT";
            message = "模型供应商调用超时。";
        }
        var providerCode = Pattern.compile("\"code\"\\s*:\\s*\"?(\\d{1,6})(?:\"|[,}\\s])").matcher(value);
        String details = "HTTP " + http + (providerCode.find() ? "; providerCode=" + providerCode.group(1) : "");
        return new EngineGatewayException(HttpStatus.BAD_GATEWAY, code, message + " (" + details + ")");
    }
}
