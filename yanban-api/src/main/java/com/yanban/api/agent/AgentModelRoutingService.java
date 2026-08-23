package com.yanban.api.agent;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * User-scoped provider failover shared by workspace runtime strategies and
 * auxiliary Agent model calls. Each candidate is resolved with its own
 * credential; credentials are never copied from the failed primary route.
 */
@Component
public class AgentModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(AgentModelRoutingService.class);

    private final ChatModelProvider models;
    private final UserSettingsService settings;

    public AgentModelRoutingService(
            @Qualifier("chatModelProvider") ChatModelProvider models,
            UserSettingsService settings) {
        this.models = models;
        this.settings = settings;
    }

    public RoutedChatResponse chat(Long userId, ChatRequest primaryRequest) {
        return chatRoutes(primaryRequest, routes(userId, primaryRequest));
    }

    /** Uses an already-frozen ordered route list, for persistent Engine tasks. */
    public RoutedChatResponse chatConfigured(
            Long userId,
            ChatRequest requestTemplate,
            List<UserSettingsService.ModelReference> frozenRoutes) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        if (frozenRoutes == null || frozenRoutes.isEmpty()) {
            throw new IllegalArgumentException("frozenRoutes must not be empty");
        }
        List<UserSettingsService.ModelEndpoint> resolved = new ArrayList<>();
        for (UserSettingsService.ModelReference route : frozenRoutes) {
            try {
                UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                        userId, route.providerKey(), route.modelName());
                if (!route.providerKey().equals(endpoint.providerKey())
                        || !route.modelName().equals(endpoint.modelName())) {
                    log.warn("agent_model_route_skipped userId={} provider={} model={} errorType=ROUTE_CHANGED",
                            userId, route.providerKey(), route.modelName());
                    resolved.add(unavailableEndpoint(route));
                    continue;
                }
                resolved.add(endpoint);
            } catch (RuntimeException unresolved) {
                log.warn("agent_model_route_skipped userId={} provider={} model={} errorType={}",
                        userId, route.providerKey(), route.modelName(),
                        unresolved.getClass().getSimpleName());
                resolved.add(unavailableEndpoint(route));
            }
        }
        return chatRoutes(requestTemplate, List.copyOf(resolved));
    }

    private RoutedChatResponse chatRoutes(
            ChatRequest primaryRequest,
            List<UserSettingsService.ModelEndpoint> routes) {
        RuntimeException lastFailure = null;
        for (int index = 0; index < routes.size(); index++) {
            UserSettingsService.ModelEndpoint endpoint = routes.get(index);
            try {
                ChatResponse response = models.chat(forEndpoint(primaryRequest, endpoint));
                if (response == null || response.message() == null) {
                    throw new ModelProviderException("Model returned an invalid response");
                }
                boolean fallbackUsed = index > 0;
                io.micrometer.core.instrument.Metrics.counter("yanban.agent.provider.routes",
                        "outcome", fallbackUsed ? "fallback" : "primary",
                        "provider", endpoint.providerKey()).increment();
                log.info("agent_model_route_selected traceId={} requestedProvider={} requestedModel={} resolvedProvider={} resolvedModel={} fallbackUsed={}",
                        primaryRequest.traceId(), primaryRequest.provider(), primaryRequest.model(),
                        endpoint.providerKey(), endpoint.modelName(), fallbackUsed);
                return new RoutedChatResponse(response, endpoint.providerKey(), endpoint.modelName(), fallbackUsed);
            } catch (RuntimeException failure) {
                if (!mayTryNext(failure)) throw failure;
                lastFailure = failure;
                io.micrometer.core.instrument.Metrics.counter("yanban.agent.provider.failures",
                        "provider", endpoint.providerKey(), "error", failure.getClass().getSimpleName()).increment();
                log.warn("agent_model_route_failed traceId={} provider={} model={} candidate={}/{} errorType={}",
                        primaryRequest.traceId(), endpoint.providerKey(), endpoint.modelName(), index + 1,
                        routes.size(), failure.getClass().getSimpleName());
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IllegalStateException("MODEL_ROUTES_EMPTY");
    }

    public Flux<ChatChunk> stream(Long userId, ChatRequest primaryRequest) {
        List<UserSettingsService.ModelEndpoint> routes = routes(userId, primaryRequest);
        return streamAttempt(primaryRequest, routes, 0);
    }

    private Flux<ChatChunk> streamAttempt(
            ChatRequest primaryRequest,
            List<UserSettingsService.ModelEndpoint> routes,
            int index) {
        if (index >= routes.size()) return Flux.error(new IllegalStateException("MODEL_ROUTES_EXHAUSTED"));
        UserSettingsService.ModelEndpoint endpoint = routes.get(index);
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(() -> models.streamChat(forEndpoint(primaryRequest, endpoint)))
                .doOnNext(ignored -> emitted.set(true))
                .doOnComplete(() -> log.info(
                        "agent_model_route_selected traceId={} requestedProvider={} requestedModel={} resolvedProvider={} resolvedModel={} fallbackUsed={}",
                        primaryRequest.traceId(), primaryRequest.provider(), primaryRequest.model(),
                        endpoint.providerKey(), endpoint.modelName(), index > 0))
                .doOnComplete(() -> io.micrometer.core.instrument.Metrics.counter(
                        "yanban.agent.provider.routes", "outcome", index > 0 ? "fallback" : "primary",
                        "provider", endpoint.providerKey()).increment())
                .onErrorResume(failure -> {
                    io.micrometer.core.instrument.Metrics.counter("yanban.agent.provider.failures",
                            "provider", endpoint.providerKey(), "error", failure.getClass().getSimpleName()).increment();
                    log.warn("agent_model_route_failed traceId={} provider={} model={} candidate={}/{} errorType={} emitted={}",
                            primaryRequest.traceId(), endpoint.providerKey(), endpoint.modelName(), index + 1,
                            routes.size(), failure.getClass().getSimpleName(), emitted.get());
                    if (!emitted.get() && mayTryNext(failure) && index + 1 < routes.size()) {
                        return streamAttempt(primaryRequest, routes, index + 1);
                    }
                    return Flux.error(failure);
                });
    }

    private List<UserSettingsService.ModelEndpoint> routes(Long userId, ChatRequest primaryRequest) {
        if (primaryRequest == null) throw new IllegalArgumentException("primaryRequest must not be null");
        List<UserSettingsService.ModelEndpoint> result = new ArrayList<>();
        result.add(new UserSettingsService.ModelEndpoint(
                primaryRequest.provider(), primaryRequest.model(), primaryRequest.apiUrl(), primaryRequest.apiKey(),
                "request", primaryRequest.provider()));
        if (userId == null || settings == null) return List.copyOf(result);

        Set<String> seen = new LinkedHashSet<>();
        seen.add(routeKey(primaryRequest.provider(), primaryRequest.model()));
        List<UserSettingsService.ModelReference> configured = settings.configuredModelReferences(userId);
        if (configured == null) return List.copyOf(result);
        for (UserSettingsService.ModelReference reference : configured) {
            if (!seen.add(routeKey(reference.providerKey(), reference.modelName()))) continue;
            try {
                result.add(settings.resolveModelEndpoint(
                        userId, reference.providerKey(), reference.modelName()));
            } catch (RuntimeException unresolved) {
                log.warn("agent_model_route_skipped userId={} provider={} model={} errorType={}",
                        userId, reference.providerKey(), reference.modelName(),
                        unresolved.getClass().getSimpleName());
            }
        }
        return List.copyOf(result);
    }

    private UserSettingsService.ModelEndpoint unavailableEndpoint(
            UserSettingsService.ModelReference route) {
        return new UserSettingsService.ModelEndpoint(
                route.providerKey(), route.modelName(), null, null,
                "unavailable", route.providerKey());
    }

    private ChatRequest forEndpoint(
            ChatRequest request,
            UserSettingsService.ModelEndpoint endpoint) {
        return new ChatRequest(
                endpoint.providerKey(), endpoint.modelName(),
                routeAwareMessages(request, endpoint),
                request.temperature(), request.maxTokens(), request.tools(),
                endpoint.apiKey(), endpoint.apiUrl(), request.responseFormat(),
                request.thinking(), request.traceId());
    }

    private List<ChatMessage> routeAwareMessages(
            ChatRequest request,
            UserSettingsService.ModelEndpoint endpoint) {
        String requestedIdentity = "provider=" + defaultString(request.provider(), "configured")
                + "; model=" + defaultString(request.model(), "configured");
        String resolvedIdentity = "provider=" + endpoint.providerKey()
                + "; model=" + endpoint.modelName();
        List<ChatMessage> messages = new ArrayList<>();
        String identityInstruction = "PaperAgent actual runtime identity for this model call is "
                + resolvedIdentity
                + ". If asked what model you are, report these exact actual values.";
        boolean identityInjected = false;
        for (ChatMessage message : request.messages()) {
            String content = message.content();
            if ("system".equals(message.role()) && content != null) {
                content = content.replace(requestedIdentity, resolvedIdentity)
                        .replace("report these exact configured values", "report these exact actual values");
                if (!identityInjected) {
                    content = identityInstruction + "\n\n" + content;
                    identityInjected = true;
                }
            }
            messages.add(new ChatMessage(
                    message.role(), content, message.toolCalls(), message.toolCallId()));
        }
        if (!identityInjected) messages.add(0, ChatMessage.system(identityInstruction));
        return List.copyOf(messages);
    }

    private String routeKey(String provider, String model) {
        return defaultString(provider, "").trim().toLowerCase(java.util.Locale.ROOT)
                + "\u0000" + defaultString(model, "").trim();
    }

    private boolean mayTryNext(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return false;
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) return false;
            current = current.getCause();
        }
        return true;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    public record RoutedChatResponse(
            ChatResponse response,
            String resolvedProvider,
            String resolvedModel,
            boolean fallbackUsed) { }
}
