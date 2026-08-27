package com.yanban.api.admin;

import com.yanban.api.demo.DemoAccessService;
import com.yanban.api.demo.DemoChatArchiveService;
import com.yanban.api.error.ApiException;
import com.yanban.api.invite.InviteCode;
import com.yanban.api.invite.InviteCodeGenerator;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final InviteCodeGenerator inviteCodeGenerator;
    private final ReactPlanAdminConversationReader reactPlanConversations;
    private final DemoChatArchiveService demoChatArchives;

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
                        InviteCodeGenerator inviteCodeGenerator,
                        ReactPlanAdminConversationReader reactPlanConversations,
                        DemoChatArchiveService demoChatArchives) {
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
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.reactPlanConversations = reactPlanConversations;
        this.demoChatArchives = demoChatArchives;
    }

    @Transactional(readOnly = true)
    public List<AdminUserSummaryResponse> listUsers() {
        return users.findByDeletedAtIsNull().stream()
                .sorted(Comparator.comparing(SysUser::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse userDetail(Long userId) {
        SysUser user = requireUser(userId);
        List<AdminUserDetailResponse.ChatSession> chats = new java.util.ArrayList<>();
        chats.addAll(sessions.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(session -> new AdminUserDetailResponse.ChatSession(
                        session.getId(), session.getTitle(), session.getScope().name(), session.getProjectId(),
                        session.getModelProviderSnapshot(), session.getModelSnapshot(), session.getCreatedAt(),
                        session.getUpdatedAt(), false, null, adminMessages(userId, session)))
                .toList());
        chats.addAll(demoChatArchives.list(userId).stream()
                .map(session -> new AdminUserDetailResponse.ChatSession(
                        session.sourceSessionId(), session.title(), session.scope(), session.projectId(),
                        session.modelProvider(), session.model(), session.createdAt(), session.updatedAt(),
                        true, session.archivedAt(), session.messages().stream()
                                .map(message -> new AdminUserDetailResponse.ChatMessage(
                                        message.id(), message.role(), message.content(), message.createdAt(),
                                        message.deletable()))
                                .toList()))
                .toList());
        chats.sort(Comparator.comparing(AdminUserDetailResponse.ChatSession::updatedAt).reversed());
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
        requireUser(userId);
        return summary(quotaService.updateQuota(userId, request.totalQuota(), request.resetUsed()));
    }

    public AdminUserSummaryResponse resetQuotaUsage(Long userId) {
        requireUser(userId);
        return summary(quotaService.resetQuotaUsage(userId));
    }

    @Transactional
    public void deleteUser(Long administratorId, Long userId) {
        SysUser administrator = requireUser(administratorId);
        if (!administrator.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以删除账号");
        }
        SysUser user = requireUser(userId);
        if (administratorId.equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "ADMIN_SELF_DELETE_FORBIDDEN", "管理员不能删除自己的账号");
        }
        if (user.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "ADMIN_ACCOUNT_DELETE_FORBIDDEN", "不能删除管理员账号");
        }
        if (DemoAccessService.ACCOUNT_TYPE_DEMO.equalsIgnoreCase(user.getAccountType())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "DEMO_ACCOUNT_DELETE_FORBIDDEN", "不能删除系统游客账号");
        }
        user.deleteAccount();
        users.saveAndFlush(user);
    }

    @Transactional(readOnly = true)
    public List<AdminInviteCodeResponse> listInviteCodes() {
        return inviteCodes.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .map(this::inviteResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public String generateInviteCode() {
        return inviteCodeGenerator.generate();
    }

    @Transactional
    public AdminInviteCodeResponse createInviteCode(AdminInviteCodeCreateRequest request) {
        if (inviteCodes.existsByCode(request.code())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "INVITE_CODE_ALREADY_EXISTS", "邀请码已存在，请重新生成",
                    java.util.Map.of("code", "邀请码已存在，请重新生成"));
        }
        try {
            return inviteResponse(inviteCodes.saveAndFlush(new InviteCode(request.code(), request.maxUses())));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "INVITE_CODE_ALREADY_EXISTS", "邀请码已存在，请重新生成",
                    java.util.Map.of("code", "邀请码已存在，请重新生成"));
        }
    }

    @Transactional
    public void deleteInviteCode(Long inviteCodeId) {
        InviteCode code = inviteCodes.findLockedById(inviteCodeId)
                .filter(value -> !value.isDeleted())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND", "邀请码不存在"));
        code.delete();
        inviteCodes.saveAndFlush(code);
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
    public void deleteArchivedDemoMessage(Long messageId) {
        demoChatArchives.deleteMessage(messageId);
    }

    @Transactional
    public void clearDemoChats() {
        for (SysUser user : users.findByAccountTypeIgnoreCase(DemoAccessService.ACCOUNT_TYPE_DEMO)) {
            demoChatArchives.clear(user.getId());
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
        long chatSessionCount = sessions.countByUserId(user.getId());
        if (DemoAccessService.ACCOUNT_TYPE_DEMO.equalsIgnoreCase(user.getAccountType())) {
            chatSessionCount += demoChatArchives.count(user.getId());
        }
        return new AdminUserSummaryResponse(user.getId(), user.getUsername(), user.getAccountType(), user.getRole(),
                user.getAiQuotaTotal(), user.getAiQuotaUsed(), user.getAiQuotaRemaining(), user.getCreatedAt(),
                user.getLastLoginAt(), chatSessionCount, papers.countByUserId(user.getId()),
                projects.countByUserId(user.getId()));
    }

    private AdminInviteCodeResponse inviteResponse(InviteCode code) {
        int remainingUses = Math.max(0, code.getMaxUses() - code.getUsedCount());
        boolean enabled = Boolean.TRUE.equals(code.getEnabled());
        String status = !enabled ? "DISABLED" : remainingUses == 0 ? "EXHAUSTED" : "AVAILABLE";
        return new AdminInviteCodeResponse(code.getId(), code.getCode(), code.getMaxUses(),
                code.getUsedCount(), remainingUses, enabled, status, code.getCreatedAt());
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
        return users.findByIdAndDeletedAtIsNull(userId)
                .map(value -> DemoAccessService.ACCOUNT_TYPE_DEMO.equalsIgnoreCase(value.getAccountType()))
                .orElse(false);
    }

    private SysUser requireUser(Long userId) {
        return users.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
    }
}
