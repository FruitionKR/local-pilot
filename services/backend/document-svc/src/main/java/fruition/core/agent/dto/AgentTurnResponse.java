package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 이 엔드포인트는 나머지 필드를 camelCase로 주고받는다.
 * applyOperationId만 snake_case인 이유는, 저장 요청이 이 값을
 * {@code @RequestPart("apply_operation_id")}로 받기 때문이다. 이름이 어긋나면
 * 클라이언트가 값을 읽지 못해 AI 작업 로그가 남지 않는다.
 *
 * @param applyOperationId 이 편집안을 적용할 때 저장 요청에 실어야 하는 표.
 *                         Backend가 발급한 값이라야 AI 작업 로그가 남는다.
 */
public record AgentTurnResponse(
        String documentId,
        long baseVersion,
        String requestId,
        @JsonProperty("apply_operation_id") String applyOperationId,
        JsonNode result
) {}
