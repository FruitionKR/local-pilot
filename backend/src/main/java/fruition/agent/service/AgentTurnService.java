package fruition.agent.service;

import fruition.agent.dto.AgentTurnRequest;
import fruition.agent.dto.AgentTurnResponse;
import fruition.agent.exception.InvalidAgentTurnRequestException;
import fruition.agent.repository.PipelineAgentRequester;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.service.DocumentEditLockService;
import fruition.document.service.DocumentService;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class AgentTurnService {

    private static final Set<String> TARGET_TYPES = Set.of("selection", "current_section", "whole_document");

    private final DocumentService documentService;
    private final DocumentEditLockService editLockService;
    private final PipelineAgentRequester pipelineAgentRequester;

    public AgentTurnService(DocumentService documentService,
                            DocumentEditLockService editLockService,
                            PipelineAgentRequester pipelineAgentRequester) {
        this.documentService = documentService;
        this.editLockService = editLockService;
        this.pipelineAgentRequester = pipelineAgentRequester;
    }

    public AgentTurnResponse turn(String workspaceId, String userId, AgentTurnRequest request) {
        DocumentDetailResponse document = documentService.findById(workspaceId, userId, request.documentId());
        if (!isMarkdown(document)) {
            throw new InvalidAgentTurnRequestException("Markdown 문서만 Agent 편집을 요청할 수 있습니다.");
        }
        // 다른 사용자가 편집 중이면 pipeline 호출 전에 423으로 거절한다.
        editLockService.requireWritable(request.documentId(), userId);
        // 오래된 snapshot(baseVersion)이면 pipeline 호출 전에 충돌로 거절해 LLM 낭비를 막는다.
        if (document.currentVersion() != request.baseVersion()) {
            throw new DocumentVersionConflictException(
                    "문서가 이미 변경되어 오래된 버전으로 편집을 요청할 수 없습니다.");
        }
        validateTarget(request.editorSnapshot());

        String requestId = "agent_" + UUID.randomUUID().toString().replace("-", "");
        return new AgentTurnResponse(
                request.documentId(),
                request.baseVersion(),
                requestId,
                pipelineAgentRequester.request(request)
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
