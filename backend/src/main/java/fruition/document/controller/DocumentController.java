package fruition.document.controller;

import fruition.util.ErrorResponse;
import fruition.document.service.DocumentService;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentStatusUpdateRequest;
import fruition.document.dto.DocumentUploadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
}
