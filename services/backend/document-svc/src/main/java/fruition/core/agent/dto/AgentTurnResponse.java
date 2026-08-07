package fruition.core.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param applyOperationId 이 편집안을 적용할 때 저장 요청에 실어야 하는 표.
 *                         Backend가 발급한 값이라야 AI 작업 로그가 남는다.
 */
public record AgentTurnResponse(
        String documentId,
        long baseVersion,
        String requestId,
        String applyOperationId,
        JsonNode result
) {}
