package com.yanban.api.admin;

import com.yanban.api.demo.DemoAccessService;
import com.yanban.api.invite.InviteCode;
import com.yanban.api.invite.InviteCodeRepository;
import com.yanban.api.project.ProjectRepository;
import com.yanban.api.project.ProjectService;
import com.yanban.api.quota.AiUsageRecord;
import com.yanban.api.quota.AiUsageRecordRepository;
import com.yanban.api.quota.UserQuotaService;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.api.agent.AgentMessageCacheService;
import com.yanban.api.agent.reactplan.ReactPlanAdminConversationReader;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.paper.domain.PaperTaskRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {

    private static final int USAGE_HISTORY_LIMIT = 100;

    private final SysUserRepository users;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentMessageCacheService messageCache;
    private final PaperTaskRepository papers;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final AiUsageRecordRepository usage;
    private final UserQuotaService quotaService;
    private final InviteCodeRepository inviteCodes;
    private final ReactPlanAdminConversationReader reactPlanConversations;

    public AdminService(SysUserRepository users,
                        AgentSessionRepository sessions,
                        AgentMessageRepository messages,
                        AgentMessageCacheService messageCache,
                        PaperTaskRepository papers,
                        ProjectRepository projects,
                        ProjectService projectService,
                        AiUsageRecordRepository usage,
                        UserQuotaService quotaService,
                        InviteCodeRepository inviteCodes,
                        ReactPlanAdminConversationReader reactPlanConversations) {
        this.users = users;
        this.sessions = sessions;
        this.messages = messages;
        this.messageCache = messageCache;
        this.papers = papers;
        this.projects = projects;
        this.projectService = projectService;
        this.usage = usage;
        this.quotaService = quotaService;
        this.inviteCodes = inviteCodes;
        this.reactPlanConversations = reactPlanConversations;
    }

    @Transactional(readOnly = true)
    public List<AdminUserSummaryResponse> listUsers() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(SysUser::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse userDetail(Long userId) {
        SysUser user = requireUser(userId);
        List<AdminUserDetailResponse.ChatSession> chats = sessions.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(session -> new AdminUserDetailResponse.ChatSession(
                        session.getId(), session.getTitle(), session.getScope().name(), session.getProjectId(),
                        session.getModelProviderSnapshot(), session.getModelSnapshot(), session.getCreatedAt(), session.getUpdatedAt(),
                        adminMessages(userId, session)))
                .toList();
        List<AdminUserDetailResponse.PaperTask> paperItems = papers.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(task -> new AdminUserDetailResponse.PaperTask(task.getId(), task.getTitle(), task.getSourceFilename(),
                        task.getStatus(), task.getCurrentStage(), task.getErrorMessage(), task.getCreatedAt(), task.getUpdatedAt()))
                .toList();
        List<AdminUserDetailResponse.Project> projectItems = projects.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(project -> new AdminUserDetailResponse.Project(project.getId(), project.getName(),
                        project.getRootType().name(), project.getIndexVersion(), project.getCreatedAt(), project.getUpdatedAt()))
                .toList();
        List<AdminUserDetailResponse.AiUsage> usageItems = usage
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, USAGE_HISTORY_LIMIT)).stream()
                .map(this::usageResponse)
                .toList();
        return new AdminUserDetailResponse(summary(user), chats, paperItems, projectItems, usageItems);
    }

    public AdminUserSummaryResponse updateQuota(Long userId, AdminQuotaUpdateRequest request) {
        return summary(quotaService.updateQuota(userId, request.totalQuota(), request.resetUsed()));
    }

    public AdminUserSummaryResponse resetQuotaUsage(Long userId) {
        return summary(quotaService.resetQuotaUsage(userId));
    }

    @Transactional(readOnly = true)
    public List<AdminInviteCodeResponse> listInviteCodes() {
        return inviteCodes.findAll().stream()
                .sorted(Comparator.comparing(InviteCode::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(code -> new AdminInviteCodeResponse(code.getId(), code.getCode(), code.getMaxUses(),
                        code.getUsedCount(), Boolean.TRUE.equals(code.getEnabled()), code.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void deleteDemoMessage(Long messageId) {
        AgentMessage message = messages.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "聊天消息不存在"));
        if (!isDemoUser(message.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除游客体验消息");
        }
        messageCache.evictSession(message.getUserId(), message.getSessionId());
        messages.delete(message);
    }

    @Transactional
    public void clearDemoChats() {
        for (SysUser user : users.findByAccountTypeIgnoreCase(DemoAccessService.ACCOUNT_TYPE_DEMO)) {
            for (AgentSession session : sessions.findByUserIdOrderByUpdatedAtDesc(user.getId())) {
                messageCache.evictSession(user.getId(), session.getId());
                sessions.delete(session);
            }
        }
    }

    @Transactional
    public void clearDemoProjects() {
        for (SysUser user : users.findByAccountTypeIgnoreCase(DemoAccessService.ACCOUNT_TYPE_DEMO)) {
            for (com.yanban.api.project.Project project : projects.findByUserIdOrderByUpdatedAtDesc(user.getId())) {
                projectService.delete(user.getId(), project.getId());
            }
        }
    }

    private AdminUserSummaryResponse summary(SysUser user) {
        return new AdminUserSummaryResponse(user.getId(), user.getUsername(), user.getAccountType(), user.getRole(),
                user.getAiQuotaTotal(), user.getAiQuotaUsed(), user.getAiQuotaRemaining(), user.getCreatedAt(),
                user.getLastLoginAt(), sessions.countByUserId(user.getId()), papers.countByUserId(user.getId()),
                projects.countByUserId(user.getId()));
    }

    private AdminUserDetailResponse.AiUsage usageResponse(AiUsageRecord record) {
        return new AdminUserDetailResponse.AiUsage(record.getId(), record.getFeature(), record.getPromptTokens(),
                record.getCompletionTokens(), record.getTotalTokens(), record.getCreatedAt());
    }

    private List<AdminUserDetailResponse.ChatMessage> adminMessages(Long userId, AgentSession session) {
        if (session.getScope() == AgentSessionScope.PROJECT) {
            List<AdminUserDetailResponse.ChatMessage> projected = reactPlanConversations
                    .read(userId, session.getId()).stream()
                    .map(message -> new AdminUserDetailResponse.ChatMessage(
                            message.id(), message.role(), message.content(), message.createdAt(), false))
                    .toList();
            if (!projected.isEmpty()) return projected;
        }
        return messages.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(message -> new AdminUserDetailResponse.ChatMessage(
                        message.getId(), message.getRole(), message.getContent(), message.getCreatedAt(), true))
                .toList();
    }

    private boolean isDemoUser(Long userId) {
        return users.findById(userId)
                .map(value -> DemoAccessService.ACCOUNT_TYPE_DEMO.equalsIgnoreCase(value.getAccountType()))
                .orElse(false);
    }

    private SysUser requireUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }
}
