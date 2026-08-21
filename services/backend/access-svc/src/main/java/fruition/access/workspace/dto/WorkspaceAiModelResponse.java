package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.Workspace;

/**
 * 워크스페이스 AI 모델 설정 응답. 기본값은 gemini + gemini-3.1-flash-lite다.
 *
 * <p>이 타입은 생성된 명세에 실리지 않는다. 응답을 내는 {@code InternalWorkspaceAiModelController}의
 * get/update가 401 본문과 성공 본문을 함께 내보내려고 {@code ResponseEntity<?>}를 반환해 타입이 지워지고,
 * 그래서 {@code api-specs/access-svc/openapi.yaml}의 응답은 {@code type: object}로 남는다.
 * {@code @Schema}를 붙여도 반영되지 않으므로 두지 않는다.
 *
 * <p>나중에 반환 타입을 {@code ResponseEntity<WorkspaceAiModelResponse>}로 좁힌다면, 그때
 * {@link WorkspaceAiModelRequest}의 동명 중첩 record와 스키마 이름이 겹치므로 한쪽에
 * {@code @Schema(name = ...)}으로 이름을 나눠야 한다.
 */
public record WorkspaceAiModelResponse(
        @JsonProperty("ingest_lint")
        AiModelSelection ingestLint) {

    public static WorkspaceAiModelResponse from(Workspace workspace) {
        return new WorkspaceAiModelResponse(new AiModelSelection(
                workspace.getIngestLintProvider(), workspace.getIngestLintModel()));
    }

    public record AiModelSelection(String provider, String model) {}
}
