package fruition.document.controller;

import fruition.util.ErrorResponse;
import fruition.document.service.DocumentService;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentOriginalResult;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.DocumentRenameResponse;
import fruition.document.dto.DocumentStatusUpdateRequest;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.PipelineEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "문서 업로드 및 조회 API")
public class DocumentController {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/markdown",
            "text/x-markdown"
    );

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "문서 업로드", description = "PDF 또는 Markdown 파일을 업로드합니다. 파일은 Object Storage에 저장되고 백그라운드에서 처리됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "업로드 성공",
            content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
        @ApiResponse(responseCode = "400", description = "파일 없음 또는 잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 업로드된 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "지원하지 않는 파일 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("INVALID_REQUEST", "파일이 없거나 비어 있습니다."));
        }

        String mimeType = file.getContentType();
        boolean isMdByExtension = file.getOriginalFilename() != null
                && file.getOriginalFilename().endsWith(".md");

        if (!ALLOWED_MIME_TYPES.contains(mimeType) && !isMdByExtension) {
            return ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of("UNSUPPORTED_FILE_TYPE", "PDF 또는 Markdown 파일만 업로드할 수 있습니다."));
        }

        DocumentUploadResponse response = documentService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "문서 목록 조회", description = "업로드된 모든 문서 목록을 반환합니다. 처리 상태 polling에 활용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentListResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<DocumentListResponse> list() {
        return ResponseEntity.ok(documentService.findAll());
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

    @Operation(summary = "문서 상세 조회", description = "특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상세 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}")
    public ResponseEntity<DocumentDetailResponse> getById(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.findById(documentId));
    }

    @Operation(summary = "원본 문서 조회", description = "MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "원본 파일 반환"),
        @ApiResponse(responseCode = "404", description = "문서 없음 또는 원본 파일 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/original")
    public ResponseEntity<InputStreamResource> getOriginal(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        DocumentOriginalResult result = documentService.getOriginal(documentId);

        String disposition = isInlineable(result.mimeType())
                ? "inline; filename=\"" + result.filename() + "\""
                : "attachment; filename=\"" + result.filename() + "\"";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(new InputStreamResource(result.inputStream()));
    }

    private boolean isInlineable(String mimeType) {
        return mimeType != null && (mimeType.startsWith("text/") || mimeType.equals("application/pdf"));
    }

    @Operation(summary = "원본 문서 block 목록 조회", description = "원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentBlocksResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/blocks")
    public ResponseEntity<DocumentBlocksResponse> blocks(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.blocks(documentId));
    }

    @Operation(summary = "문서 삭제", description = "문서와 연결된 source Wiki 페이지, MinIO 오브젝트를 삭제합니다. concept Wiki 페이지는 삭제되지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{document_id}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        documentService.delete(documentId);
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
        documentService.applyPipelineEvent(documentId, request.runId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "문서 이름 변경", description = "문서 표시명을 변경합니다. sync_source_title=true이면 연결된 source Wiki 페이지 제목도 함께 변경됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이름 변경 성공",
            content = @Content(schema = @Schema(implementation = DocumentRenameResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 파일명",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{document_id}/rename")
    public ResponseEntity<DocumentRenameResponse> rename(
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @RequestBody DocumentRenameRequest request) {
        return ResponseEntity.ok(documentService.rename(documentId, request));
    }
}
