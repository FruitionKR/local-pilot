package fruition.core.aihistory.controller;

import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.OperationResultResponse;
import fruition.core.aihistory.exception.InvalidCallbackTokenException;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * llmPipeline이 호출하는 내부 콜백. 사용자 인증 대상이 아니며 내부 토큰으로 검증한다.
 *
 * <p>인증을 통과하기 전에는 저장소 객체를 읽지 않는다.
 */
@RestController
@RequestMapping("/api/ai-operations")
@Tag(name = "AI Operations (Pipeline)",
        description = "llmPipeline이 작업 결과를 알리는 내부 콜백 API입니다.")
public class OperationCallbackController {

    private final OperationIngestService ingestService;
    private final String internalToken;

    public OperationCallbackController(OperationIngestService ingestService,
                                       @Value("${app.internal.callback-token}") String internalToken) {
        this.ingestService = ingestService;
        this.internalToken = internalToken;
    }

    @Operation(summary = "AI 작업 결과 수신",
            description = "ingest 또는 복구 재조립이 끝나면 llmPipeline이 호출합니다. "
                    + "작업이 restore면 재조립 분기로 가며 failed_pages를 함께 받습니다. "
                    + "같은 payload 재전송은 200, 같은 작업에 다른 payload가 오면 409입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "반영 완료"),
            @ApiResponse(responseCode = "401", description = "내부 토큰 불일치",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "등록되지 않은 작업",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "같은 작업에 다른 결과가 도착",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "본문 경로·해시 검증 실패. 다시 쓰고 재전송",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{operation_id}/result")
    public ResponseEntity<OperationResultResponse> result(
            @Parameter(description = "작업 식별자", example = "op_a2_7f3c9")
            @PathVariable("operation_id") String operationId,
            @Parameter(description = "내부 콜백 토큰", required = true)
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody OperationResultRequest request) {
        verifyToken(token);
        return ResponseEntity.ok(ingestService.accept(operationId, request));
    }

    /** 길이가 달라도 시간차가 새지 않도록 상수 시간 비교를 쓴다. */
    private void verifyToken(String token) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackTokenException();
        }
    }
}
