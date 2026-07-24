package fruition.document.controller;

import fruition.util.ErrorResponse;
import fruition.document.service.DocumentService;
import fruition.document.service.DocumentExportService;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentContentSaveResponse;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentDuplicateResponse;
import fruition.document.dto.DocumentExportResult;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentLifecycleRequest;
import fruition.document.dto.DocumentLifecycleResponse;
import fruition.document.dto.MarkdownDocumentCreateRequest;
import fruition.document.dto.DocumentOriginalResult;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.DocumentRenameResponse;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.DocumentTrashResponse;
import fruition.document.exception.InvalidDocumentVersionException;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents")
@Tag(name = "Documents", description = "문서 업로드 및 조회 API")
public class DocumentController {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/markdown",
            "text/x-markdown"
    );

    private final DocumentService documentService;
    private final DocumentExportService documentExportService;

    public DocumentController(
            DocumentService documentService,
            DocumentExportService documentExportService
    ) {
        this.documentService = documentService;
        this.documentExportService = documentExportService;
    }

    @Operation(
        summary = "문서 업로드",
        description = "PDF 또는 Markdown 파일을 업로드합니다. Markdown은 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "업로드 성공",
            content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
        @ApiResponse(responseCode = "400", description = "파일 없음 또는 잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 업로드된 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "지원하지 않는 파일 형식",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("INVALID_REQUEST", "파일이 없거나 비어 있습니다."));
        }

        String mimeType = file.getContentType();
        String normalizedFilename = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        boolean isMdByExtension = normalizedFilename.endsWith(".md")
                || normalizedFilename.endsWith(".markdown");

        if (!ALLOWED_MIME_TYPES.contains(mimeType) && !isMdByExtension) {
            return ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of("UNSUPPORTED_FILE_TYPE", "PDF 또는 Markdown 파일만 업로드할 수 있습니다."));
        }

        DocumentUploadResponse response = documentService.upload(workspaceId, userId, idempotencyKey, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(path = "/markdown", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentUploadResponse> createMarkdown(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody MarkdownDocumentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.createMarkdown(workspaceId, userId, idempotencyKey, request));
    }

    @Operation(summary = "문서 목록 조회", description = "워크스페이스에 업로드된 모든 문서 목록을 반환합니다. 처리 상태 polling에 활용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentListResponse.class))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<DocumentListResponse> list(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestParam(value = "query", required = false) String query) {
        return ResponseEntity.ok(documentService.findAll(workspaceId, userId, query));
    }

    @Operation(summary = "문서 상세 조회", description = "특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상세 조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}")
    public ResponseEntity<DocumentDetailResponse> getById(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.findById(workspaceId, userId, documentId));
    }

    @Operation(summary = "원본 문서 조회", description = "MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "원본 파일 반환"),
        @ApiResponse(responseCode = "404", description = "문서, 원본 파일 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/original")
    public ResponseEntity<InputStreamResource> getOriginal(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        DocumentOriginalResult result = documentService.getOriginal(workspaceId, userId, documentId);

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

    @Operation(
        summary = "Markdown 원문 내보내기",
        description = "요청 시점의 최신 Markdown 편집본을 UTF-8 .md 파일로 다운로드합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Markdown 다운로드"),
        @ApiResponse(responseCode = "404", description = "workspace, Markdown 문서 또는 편집 상태를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/export")
    public ResponseEntity<InputStreamResource> export(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId) {
        DocumentExportResult result =
                documentExportService.exportMarkdown(workspaceId, userId, documentId);
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .contentLength(result.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new InputStreamResource(new ByteArrayInputStream(result.bytes())));
    }

    @Operation(summary = "원본 문서 block 목록 조회", description = "원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = DocumentBlocksResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{document_id}/blocks")
    public ResponseEntity<DocumentBlocksResponse> blocks(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId) {
        return ResponseEntity.ok(documentService.blocks(workspaceId, userId, documentId));
    }

    @Operation(summary = "문서 삭제", description = "원본과 편집 상태를 유지한 채 문서를 소프트 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "삭제 성공",
            content = @Content(schema = @Schema(implementation = DocumentLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 base_version 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 또는 멱등 키 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{document_id}")
    public ResponseEntity<DocumentLifecycleResponse> delete(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentLifecycleRequest request) {
        return ResponseEntity.ok(documentService.delete(
                workspaceId, userId, documentId, idempotencyKey, request));
    }

    @Operation(summary = "문서 휴지통", description = "워크스페이스에서 소프트 삭제된 문서를 반환합니다.")
    @GetMapping("/trash")
    public ResponseEntity<DocumentTrashResponse> trash(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(documentService.trash(workspaceId, userId));
    }

    @Operation(summary = "문서 복구", description = "삭제 문서를 역할별 최상위 마지막 위치에 복구합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "복구 성공",
            content = @Content(schema = @Schema(implementation = DocumentLifecycleResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 base_version 또는 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "삭제 문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 또는 멱등 키 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/restore")
    public ResponseEntity<DocumentLifecycleResponse> restore(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentLifecycleRequest request) {
        return ResponseEntity.ok(documentService.restore(
                workspaceId, userId, documentId, idempotencyKey, request));
    }

    @Operation(summary = "문서 이름 변경", description = "Notion의 page title처럼 표시 이름만 변경하며 본문과 Wiki 제목은 유지합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이름 변경 성공",
            content = @Content(schema = @Schema(implementation = DocumentRenameResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 파일명",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{document_id}/rename")
    public ResponseEntity<DocumentRenameResponse> rename(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "문서 ID", example = "doc_abc12345")
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentRenameRequest request) {
        return ResponseEntity.ok(documentService.rename(workspaceId, userId, documentId, request));
    }

    @Operation(
        summary = "Markdown 문서 복제",
        description = "문서 소유자가 최신 Markdown 편집본을 같은 부모의 마지막 위치에 새 문서로 복제합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "복제 성공 또는 멱등 재요청",
            content = @Content(schema = @Schema(implementation = DocumentDuplicateResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아니거나 편집 문서가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{document_id}/duplicate")
    public ResponseEntity<DocumentDuplicateResponse> duplicate(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.duplicate(workspaceId, userId, documentId, idempotencyKey));
    }

    @Operation(
        summary = "Markdown 본문 저장",
        description = "전체 Markdown을 수동 저장합니다. base_version이 현재 version과 일치할 때만 반영합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "저장 성공 또는 동일 본문 no-op",
            content = @Content(schema = @Schema(implementation = DocumentContentSaveResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Markdown 또는 base_version",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "문서 소유자가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "문서 또는 워크스페이스를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "문서 version 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Markdown 5MB 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping(path = "/{document_id}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentContentSaveResponse> saveContent(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Parameter(description = "저장할 전체 Markdown 본문", required = true)
            @RequestPart("markdown") String markdown,
            @Parameter(description = "클라이언트가 조회한 현재 문서 version", example = "1", required = true)
            @RequestPart("base_version") String baseVersion) {
        return ResponseEntity.ok(
                documentService.saveContent(
                        workspaceId, userId, documentId, markdown, parseBaseVersion(baseVersion)));
    }

    private long parseBaseVersion(String baseVersion) {
        try {
            long parsed = Long.parseLong(baseVersion);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidDocumentVersionException(
                    "base_version은 1 이상의 정수여야 합니다.");
        }
    }
}
