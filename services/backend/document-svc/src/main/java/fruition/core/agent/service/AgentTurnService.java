package fruition.core.agent.service;

import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentTurnService {

    private static final Set<String> TARGET_TYPES = Set.of("selection", "current_section", "whole_document");
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("agent_[0-9a-f]{32}");

    private final DocumentService documentService;
    private final DocumentEditLockService editLockService;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AgentRunCommandRepository runRepository;
    private final PipelineAgentRunStatusRequester statusRequester;
    private final AiCommandOutboxWriter outboxWriter;
    private final AgentApplyOperationStore applyOperationStore;
    private final String commandTopic;

    public AgentTurnService(DocumentService documentService,
                            DocumentEditLockService editLockService,
                            WorkspaceAccessGuard workspaceAccessGuard,
                            AgentRunCommandRepository runRepository,
                            PipelineAgentRunStatusRequester statusRequester,
                            AiCommandOutboxWriter outboxWriter,
                            AgentApplyOperationStore applyOperationStore,
                            @Value("${app.agent.command-topic}") String commandTopic) {
        this.documentService = documentService;
        this.editLockService = editLockService;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.runRepository = runRepository;
        this.statusRequester = statusRequester;
        this.outboxWriter = outboxWriter;
        this.applyOperationStore = applyOperationStore;
        this.commandTopic = commandTopic;
    }

    @Transactional
    public AgentTurnResponse turn(String workspaceId, String userId, AgentTurnRequest request) {
        DocumentDetailResponse document = documentService.findById(workspaceId, userId, request.documentId());
        if (!isMarkdown(document)) {
            throw new InvalidAgentTurnRequestException("Markdown 문서만 Agent 편집을 요청할 수 있습니다.");
        }
        // 다른 사용자가 편집 중이면 pipeline 호출 전에 423으로 거절한다.
        editLockService.requireWritable(request.documentId(), userId);
        // 오래된 snapshot(baseVersion)이면 pipeline 호출 전에 충돌로 거절해 LLM 낭비를 막는다.
        // 본문 편집 기준 version은 Mongo edit_revision이다(없으면 current_version과 같다).
        if (document.editRevision() != request.baseVersion()) {
            throw new DocumentVersionConflictException(
                    "문서가 이미 변경되어 오래된 버전으로 편집을 요청할 수 없습니다.");
        }
        validateTarget(request.editorSnapshot());

        String runId = "agent_" + UUID.randomUUID().toString().replace("-", "");
        // 편집안을 적용할 때 되돌려받을 표. source=agent 문자열 대신 이 값으로 AI 작업 여부를 가린다.
        String applyOperationId = applyOperationStore.newOperationId();
        runRepository.create(runId, workspaceId, userId, request.documentId(),
                request.baseVersion(), applyOperationId);
        outboxWriter.enqueue(runId, commandTopic, request.documentId(),
                new AgentCommand(runId, "agent", workspaceId, userId, request.documentId(),
                        request.baseVersion(), applyOperationId, request.message(),
                        CommandConversationContext.from(request.conversationContext()),
                        CommandEditorSnapshot.from(request.editorSnapshot())));
        return new AgentTurnResponse(
                request.documentId(),
                request.baseVersion(),
                runId,
                applyOperationId,
                "queued",
                null,
                null
        );
    }

    public AgentTurnResponse get(String workspaceId, String userId, String runId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new InvalidAgentTurnRequestException("Agent run ID 형식이 올바르지 않습니다.");
        }
        var aiRun = statusRequester.find(workspaceId, userId, runId);
        if (aiRun.isPresent()) {
            var run = aiRun.get();
            if ("completed".equals(run.status()) || "failed".equals(run.status())) {
                var projection = runRepository.find(workspaceId, userId, runId)
                        .orElseThrow(() -> new AgentRunNotFoundException(runId));
                if ("queued".equals(projection.status())) {
                    return new AgentTurnResponse(projection.documentId(), projection.baseVersion(),
                            projection.runId(), projection.applyOperationId(), projection.status(),
                            projection.result(), projection.error());
                }
            }
            return new AgentTurnResponse(run.documentId(), run.baseVersion(), run.id(),
                    run.applyOperationId(), run.status(), run.result(), run.errorCode());
        }
        var queued = runRepository.find(workspaceId, userId, runId)
                .filter(projection -> "queued".equals(projection.status()))
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        return new AgentTurnResponse(queued.documentId(), queued.baseVersion(), queued.runId(),
                queued.applyOperationId(), queued.status(), queued.result(), queued.error());
    }

    private boolean isMarkdown(DocumentDetailResponse document) {
        return "text/markdown".equalsIgnoreCase(document.mimeType())
                || document.filename().toLowerCase().endsWith(".md")
                || document.filename().toLowerCase().endsWith(".markdown");
    }

    private void validateTarget(AgentTurnRequest.EditorSnapshot snapshot) {
        AgentTurnRequest.Target target = snapshot.target();
        if (target == null) return;
        int lineCount = snapshot.markdown().split("\\n", -1).length;
        if (!TARGET_TYPES.contains(target.type())
                || target.endLine() < target.startLine()
                || target.endLine() > lineCount) {
            throw new InvalidAgentTurnRequestException("Markdown 편집 범위가 올바르지 않습니다.");
        }
    }

    record AgentCommand(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
            String kind,
            @com.fasterxml.jackson.annotation.JsonProperty("workspace_id") String workspaceId,
            @com.fasterxml.jackson.annotation.JsonProperty("user_id") String userId,
            @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId,
            @com.fasterxml.jackson.annotation.JsonProperty("base_version") long baseVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("apply_operation_id") String applyOperationId,
            String message,
            @com.fasterxml.jackson.annotation.JsonProperty("conversation_context") CommandConversationContext conversationContext,
            @com.fasterxml.jackson.annotation.JsonProperty("editor_snapshot") CommandEditorSnapshot editorSnapshot
    ) {}

    record CommandConversationContext(
            @com.fasterxml.jackson.annotation.JsonProperty("recent_conversation_summary") String recentConversationSummary,
            @com.fasterxml.jackson.annotation.JsonProperty("reference_context") java.util.Map<String, Object> referenceContext
    ) {
        static CommandConversationContext from(AgentTurnRequest.ConversationContext context) {
            return context == null ? null
                    : new CommandConversationContext(context.recentConversationSummary(), context.referenceContext());
        }
    }

    record CommandEditorSnapshot(String markdown, CommandTarget target) {
        static CommandEditorSnapshot from(AgentTurnRequest.EditorSnapshot snapshot) {
            return new CommandEditorSnapshot(snapshot.markdown(), CommandTarget.from(snapshot.target()));
        }
    }

    record CommandTarget(
            String type,
            @com.fasterxml.jackson.annotation.JsonProperty("start_line") int startLine,
            @com.fasterxml.jackson.annotation.JsonProperty("end_line") int endLine
    ) {
        static CommandTarget from(AgentTurnRequest.Target target) {
            return target == null ? null : new CommandTarget(target.type(), target.startLine(), target.endLine());
        }
    }
}
