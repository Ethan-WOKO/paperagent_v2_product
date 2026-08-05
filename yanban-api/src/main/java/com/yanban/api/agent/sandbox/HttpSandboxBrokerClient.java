package com.yanban.api.agent.sandbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.http.HttpStatusCode;

/** API-side client only. E2B credentials remain in the separately deployed broker. */
@Component
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
final class HttpSandboxBrokerClient implements SandboxBrokerClient {
    private final RestClient client;
    private final ObjectMapper json;
    private static final int MAX_RESPONSE_BYTES = 21 * 1024 * 1024;

    HttpSandboxBrokerClient(RestClient.Builder builder, SandboxExecutionProperties properties, ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.baseUrl(properties.getBrokerUrl().toString())
                .requestFactory(requests).defaultHeader("Authorization", "Bearer " + properties.getBrokerToken()).build();
        this.json = json;
    }

    @Override public void requireHealthy() {
        try { client.get().uri("/internal/v1/health").retrieve().toBodilessEntity(); }
        catch (Exception ex) { throw new IllegalStateException("required sandbox broker is unavailable", ex); }
    }

    @Override public SandboxDispatchResponse dispatch(SandboxDispatch request) {
        try {
            SandboxDispatchResponse response = client.post().uri("/internal/v1/executions")
                    .body(request).exchange((ignored,res) -> decode(res.getStatusCode(),res.getBody(),SandboxDispatchResponse.class));
            if (response == null) throw new IllegalStateException("empty broker response");
            return response;
        } catch (SandboxExecutionException ex) { throw ex;
        } catch (Exception ex) {
            throw new SandboxExecutionException(SandboxFailureCode.SANDBOX_UNAVAILABLE,
                    "sandbox broker is unavailable", ex);
        }
    }

    @Override public SandboxExecutionView status(String executionId) {
        try { return client.get().uri("/internal/v1/executions/{id}",executionId)
                .exchange((ignored,res)->decode(res.getStatusCode(),res.getBody(),SandboxExecutionView.class)); }
        catch(SandboxExecutionException ex){throw ex;}catch(Exception ex){throw new SandboxExecutionException(SandboxFailureCode.SANDBOX_UNAVAILABLE,"sandbox status unavailable",ex);}
    }

    @Override public void cancel(String executionId, long fence) {
        try {
            client.post().uri(uri -> uri.path("/internal/v1/executions/{id}/cancel")
                    .queryParam("fence", fence).build(executionId)).exchange((ignored,res)->{if(res.getStatusCode().is2xxSuccessful()){res.getBody().readNBytes(1);return null;}decode(res.getStatusCode(),res.getBody(),SandboxErrorResponse.class);return null;});
        } catch (SandboxExecutionException ex) { throw ex;
        } catch (Exception ex) {
            throw new SandboxExecutionException(SandboxFailureCode.SANDBOX_UNAVAILABLE,
                    "sandbox cancellation could not be confirmed", ex);
        }
    }

    private <T> T decode(HttpStatusCode status, java.io.InputStream input, Class<T> type) throws IOException {
        byte[] bytes=input.readNBytes(MAX_RESPONSE_BYTES+1);
        if(bytes.length>MAX_RESPONSE_BYTES)throw new SandboxExecutionException(SandboxFailureCode.PROVIDER_REJECTED,"sandbox broker response exceeds limit");
        if(status.is2xxSuccessful())return json.readValue(bytes,type);
        SandboxErrorResponse error;
        try{error=json.readValue(bytes,SandboxErrorResponse.class);}catch(Exception malformed){throw new SandboxExecutionException(
                status.is5xxServerError()?SandboxFailureCode.SANDBOX_UNAVAILABLE:SandboxFailureCode.PROVIDER_REJECTED,"sandbox broker returned an invalid error",malformed);}
        throw new SandboxExecutionException(map(error),"sandbox broker rejected the request");
    }
    private SandboxFailureCode map(SandboxErrorResponse error){return switch(error.code()){
        case DIGEST_CONFLICT -> SandboxFailureCode.RECEIPT_CONFLICT;
        case REQUEST_TOO_LARGE, CONCURRENCY_EXHAUSTED, PROVIDER_REJECTED, POLICY_REJECTED -> SandboxFailureCode.PROVIDER_REJECTED;
        case CANCELLED -> SandboxFailureCode.CANCELLED;
        case TIMED_OUT -> SandboxFailureCode.TIMED_OUT;
        case STALE_FENCE, UNAUTHORIZED -> SandboxFailureCode.AUTHORITY_REJECTED;
        case CLEANUP_FAILED -> SandboxFailureCode.CLEANUP_FAILED;
        case PROVIDER_UNAVAILABLE -> SandboxFailureCode.SANDBOX_UNAVAILABLE;
    };}
}
