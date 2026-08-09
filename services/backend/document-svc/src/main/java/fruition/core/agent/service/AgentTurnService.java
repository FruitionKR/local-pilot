package fruition.core.agent.service;

import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.dto.AgentTurnResponse;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.PipelineAgentRequester;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class AgentTurnService {

    private static final Set<String> TARGET_TYPES = Set.of("selection", "current_section", "whole_document");

    private final DocumentService documentService;
    private final DocumentEditLockService editLockService;
    private final PipelineAgentRequester pipelineAgentRequester;
    private final AgentApplyOperationStore applyOperationStore;

    public AgentTurnService(DocumentService documentService,
                            DocumentEditLockService editLockService,
                            PipelineAgentRequester pipelineAgentRequester,
                            AgentApplyOperationStore applyOperationStore) {
        this.documentService = documentService;
        this.editLockService = editLockService;
        this.pipelineAgentRequester = pipelineAgentRequester;
        this.applyOperationStore = applyOperationStore;
    }

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

        String requestId = "agent_" + UUID.randomUUID().toString().replace("-", "");
        // 편집안을 적용할 때 되돌려받을 표. source=agent 문자열 대신 이 값으로 AI 작업 여부를 가린다.
        String applyOperationId = applyOperationStore.issue(userId, request.documentId());
        return new AgentTurnResponse(
                request.documentId(),
                request.baseVersion(),
                requestId,
                applyOperationId,
                pipelineAgentRequester.request(workspaceId, userId, request)
        );
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
}
