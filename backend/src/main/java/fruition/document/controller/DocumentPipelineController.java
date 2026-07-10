package fruition.document.controller;

import fruition.util.ErrorResponse;
import fruition.document.service.DocumentService;
import fruition.document.dto.DocumentStatusUpdateRequest;
import fruition.document.dto.PipelineEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents (Pipeline)", description = "llmPipeline이 호출하는 내부 콜백 API. workspace_id를 알지 못하는 문서 처리 단계에서 사용됩니다.")
public class DocumentPipelineController {

    private final DocumentService documentService;

    public DocumentPipelineController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "문서 처리 상태 업데이트",
        description = "FastAPI 파이프라인이 문서 처리 단계마다 호출하는 콜백 엔드포인트입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "상태 업데이트 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{document_id}/status")
    public ResponseEntity<Void> updateStatus(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentStatusUpdateRequest request) {
        documentService.updateStatus(documentId, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "파이프라인 이벤트 수신", description = "llmPipeline이 처리 단계마다 호출하는 heartbeat callback입니다. processing_updated_at을 갱신합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "이벤트 처리 완료"),
        @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/pipeline-events")
    public ResponseEntity<Void> pipelineEvent(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @RequestBody PipelineEventRequest request) {
        documentService.applyPipelineEvent(documentId, request.runId(), request.stage());
        return ResponseEntity.noContent().build();
    }
}
