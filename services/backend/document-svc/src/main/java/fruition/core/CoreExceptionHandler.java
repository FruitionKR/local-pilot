package fruition.core;

import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.core.ai.WorkspaceAiModelForbiddenException;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.InvalidCallbackTokenException;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.OperationPayloadConflictException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.exception.WikiObjectReadException;
import fruition.core.wikischema.exception.PipelineWikiSchemaException;
import fruition.core.wikimaintenance.exception.PipelineWikiMaintenanceException;
import fruition.core.document.exception.DocumentAlreadyProcessingException;
import fruition.core.document.exception.DocumentContentVersionNotFoundException;
import fruition.core.document.exception.DocumentLockedException;
import fruition.core.document.exception.EditLockLostException;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentOriginalNotFoundException;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.DuplicateDocumentException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidDocumentVersionException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.exception.MarkdownDiffTooLargeException;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.document.exception.HierarchyVersionConflictException;
import fruition.core.document.exception.HierarchyCycleException;
import fruition.core.document.exception.InvalidHierarchyRequestException;
import fruition.core.document.exception.HierarchyWriteForbiddenException;
import fruition.core.chat.exception.ChatSessionLimitExceededException;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.exception.EmptyChatWikiExportException;
import fruition.core.chat.exception.InvalidChatWikiExportRequestException;
import fruition.core.query.exception.PipelineQueryException;
import fruition.core.query.exception.QueryRunNotFoundException;
import fruition.core.wiki.exception.InvalidWikiPageTitleException;
import fruition.core.wiki.exception.PipelineWikiPageException;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.exception.WikiPageSlugConflictException;
import fruition.core.wiki.exception.WikiPageVersionNotFoundException;
import fruition.shared.util.BaseExceptionHandler;
import fruition.shared.util.ErrorResponse;
import fruition.core.document.exception.DocumentAssetExportException;
import fruition.core.document.exception.DocumentAssetNotFoundException;
import fruition.core.document.exception.DocumentAssetStorageException;
import fruition.core.document.exception.DocumentAssetTooLargeException;
import fruition.core.document.exception.InvalidDocumentAssetException;
import fruition.core.document.exception.UnsupportedDocumentAssetException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** document 앱 전용 예외 매핑. 공통 매핑은 {@link BaseExceptionHandler}에서 상속한다. */
@RestControllerAdvice
public class CoreExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(PipelineWikiPageException.class)
    public ResponseEntity<?> handlePipelineWikiPage(PipelineWikiPageException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return ResponseEntity.status(e.getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBody());
        }
        return ResponseEntity.status(e.getHttpStatus())
                .body(ErrorResponse.of("WIKI_PAGE_PIPELINE_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(PipelineAgentException.class)
    public ResponseEntity<?> handlePipelineAgent(PipelineAgentException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return ResponseEntity.status(e.getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBody());
        }
        return ResponseEntity.status(e.getHttpStatus())
                .body(ErrorResponse.of("AGENT_PIPELINE_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(InvalidAgentTurnRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAgentTurnRequest(InvalidAgentTurnRequestException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(AgentRunNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAgentRunNotFound(AgentRunNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("AGENT_RUN_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(PipelineWikiSchemaException.class)
    public ResponseEntity<?> handlePipelineWikiSchema(PipelineWikiSchemaException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return ResponseEntity.status(e.getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBody());
        }
        return ResponseEntity.status(e.getHttpStatus())
                .body(ErrorResponse.of("WIKI_SCHEMA_PIPELINE_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(PipelineWikiMaintenanceException.class)
    public ResponseEntity<?> handlePipelineWikiMaintenance(PipelineWikiMaintenanceException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return ResponseEntity.status(e.getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBody());
        }
        return ResponseEntity.status(e.getHttpStatus())
                .body(ErrorResponse.of("WIKI_MAINTENANCE_PIPELINE_UNAVAILABLE", e.getMessage()));
    }

    /** multipart 한도를 넘으면 Spring이 요청을 읽기 전에 막는다. 크기 문제임을 413으로 구분해 알린다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("PAYLOAD_TOO_LARGE", "요청 크기가 허용 한도를 초과했습니다."));
    }


    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(DocumentNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("DOCUMENT_NOT_FOUND", "문서를 찾을 수 없습니다."));
    }

    @ExceptionHandler(DocumentAssetNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentAssetNotFound(DocumentAssetNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("DOCUMENT_ASSET_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(DocumentAssetExportException.class)
    public ResponseEntity<ErrorResponse> handleDocumentAssetExport(DocumentAssetExportException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("DOCUMENT_ASSET_EXPORT_FAILED", e.getMessage()));
    }

    @ExceptionHandler(DocumentWriteForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleDocumentWriteForbidden(DocumentWriteForbiddenException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("DOCUMENT_WRITE_FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(DocumentVersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleDocumentVersionConflict(DocumentVersionConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DOCUMENT_VERSION_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(DocumentAlreadyProcessingException.class)
    public ResponseEntity<ErrorResponse> handleDocumentAlreadyProcessing(DocumentAlreadyProcessingException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DOCUMENT_ALREADY_PROCESSING", e.getMessage()));
    }

    @ExceptionHandler(DocumentContentVersionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentContentVersionNotFound(DocumentContentVersionNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("DOCUMENT_CONTENT_VERSION_NOT_FOUND", "문서 콘텐츠 버전을 찾을 수 없습니다."));
    }

    @ExceptionHandler(DocumentLockedException.class)
    public ResponseEntity<ErrorResponse> handleDocumentLocked(DocumentLockedException e) {
        return ResponseEntity
                .status(HttpStatus.LOCKED)
                .body(ErrorResponse.of("DOCUMENT_EDIT_LOCKED", e.getMessage()));
    }

    @ExceptionHandler(EditLockLostException.class)
    public ResponseEntity<ErrorResponse> handleEditLockLost(EditLockLostException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("EDIT_LOCK_LOST", e.getMessage()));
    }

    @ExceptionHandler(DocumentOriginalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentOriginalNotFound(DocumentOriginalNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("DOCUMENT_ORIGINAL_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidDocumentFilenameException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocumentFilename(InvalidDocumentFilenameException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_DOCUMENT_FILENAME", e.getMessage()));
    }

    @ExceptionHandler(InvalidDocumentVersionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocumentVersion(InvalidDocumentVersionException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_DOCUMENT_VERSION", e.getMessage()));
    }

    @ExceptionHandler(InvalidDocumentConvertRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocumentConvertRequest(InvalidDocumentConvertRequestException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_DOCUMENT_CONVERT_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(InvalidMarkdownContentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMarkdownContent(InvalidMarkdownContentException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_MARKDOWN_CONTENT", e.getMessage()));
    }

    @ExceptionHandler(InvalidDocumentAssetException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocumentAsset(InvalidDocumentAssetException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_DOCUMENT_ASSET", e.getMessage()));
    }

    @ExceptionHandler(DocumentAssetTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleDocumentAssetTooLarge(DocumentAssetTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("DOCUMENT_ASSET_TOO_LARGE", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedDocumentAssetException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedDocumentAsset(UnsupportedDocumentAssetException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of("UNSUPPORTED_DOCUMENT_ASSET", e.getMessage()));
    }

    @ExceptionHandler(DocumentAssetStorageException.class)
    public ResponseEntity<ErrorResponse> handleDocumentAssetStorage(DocumentAssetStorageException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("DOCUMENT_ASSET_STORAGE_FAILED", e.getMessage()));
    }

    @ExceptionHandler(MarkdownContentTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleMarkdownContentTooLarge(MarkdownContentTooLargeException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("MARKDOWN_CONTENT_TOO_LARGE", e.getMessage()));
    }

    @ExceptionHandler(MarkdownDiffTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleMarkdownDiffTooLarge(MarkdownDiffTooLargeException e) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("MARKDOWN_DIFF_TOO_LARGE", e.getMessage()));
    }

    @ExceptionHandler(InvalidHierarchyRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidHierarchyRequest(InvalidHierarchyRequestException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_HIERARCHY_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(HierarchyItemNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleHierarchyItemNotFound(HierarchyItemNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("HIERARCHY_ITEM_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(HierarchyVersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleHierarchyVersionConflict(HierarchyVersionConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("HIERARCHY_VERSION_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(HierarchyCycleException.class)
    public ResponseEntity<ErrorResponse> handleHierarchyCycle(HierarchyCycleException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("HIERARCHY_CYCLE", e.getMessage()));
    }

    @ExceptionHandler(HierarchyWriteForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleHierarchyWriteForbidden(HierarchyWriteForbiddenException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("HIERARCHY_WRITE_FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(EmptyChatWikiExportException.class)
    public ResponseEntity<ErrorResponse> handleEmptyChatWikiExport(EmptyChatWikiExportException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("EMPTY_CHAT_WIKI_EXPORT", e.getMessage()));
    }

    @ExceptionHandler(InvalidChatWikiExportRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidChatWikiExportRequest(InvalidChatWikiExportRequestException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_CHAT_WIKI_EXPORT_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(WikiPageNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWikiPageNotFound(WikiPageNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WIKI_PAGE_NOT_FOUND", "Wiki 페이지를 찾을 수 없습니다."));
    }

    @ExceptionHandler(InvalidWikiPageTitleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWikiPageTitle(InvalidWikiPageTitleException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_WIKI_PAGE_TITLE", e.getMessage()));
    }

    @ExceptionHandler(WikiPageSlugConflictException.class)
    public ResponseEntity<ErrorResponse> handleWikiPageSlugConflict(WikiPageSlugConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("WIKI_PAGE_SLUG_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateDocument(DuplicateDocumentException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DOCUMENT_ALREADY_EXISTS", e.getMessage()));
    }

    @ExceptionHandler(DocumentUploadException.class)
    public ResponseEntity<ErrorResponse> handleDocumentUpload(DocumentUploadException e) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 처리 중 오류가 발생했습니다."));
    }

    @ExceptionHandler(PipelineQueryException.class)
    public ResponseEntity<ErrorResponse> handlePipelineQuery(PipelineQueryException e) {
        return ResponseEntity
                .status(e.getHttpStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(QueryRunNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleQueryRunNotFound(QueryRunNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("QUERY_RUN_NOT_FOUND", e.getMessage()));
    }

    // core(문서 서비스) 소유 guard가 던지는 예외. access의 동명 예외와 동일하게 404로 매핑한다.
    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(WorkspaceNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WORKSPACE_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(WorkspaceAiModelForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceAiModelForbidden(WorkspaceAiModelForbiddenException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("WORKSPACE_AI_MODEL_FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(OperationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOperationNotFound(OperationNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("AI_OPERATION_NOT_FOUND", "AI 작업 로그를 찾을 수 없습니다."));
    }

    @ExceptionHandler(InvalidRestoreRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRestoreRequest(InvalidRestoreRequestException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_RESTORE_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(WikiPageVersionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWikiPageVersionNotFound(WikiPageVersionNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WIKI_PAGE_VERSION_NOT_FOUND", "Wiki 페이지 버전을 찾을 수 없습니다."));
    }

    @ExceptionHandler(InvalidCallbackTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCallbackToken(InvalidCallbackTokenException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_CALLBACK_TOKEN", e.getMessage()));
    }

    /** 다시 쓰고 재전송하면 성공할 수 있는 실패라 422로 알린다. */
    @ExceptionHandler(InvalidCallbackPayloadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCallbackPayload(InvalidCallbackPayloadException e) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("INVALID_CALLBACK_PAYLOAD", e.getMessage()));
    }

    @ExceptionHandler(OperationPayloadConflictException.class)
    public ResponseEntity<ErrorResponse> handleOperationPayloadConflict(OperationPayloadConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("AI_OPERATION_PAYLOAD_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(WikiObjectReadException.class)
    public ResponseEntity<ErrorResponse> handleWikiObjectRead(WikiObjectReadException e) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("WIKI_OBJECT_READ_FAILED", e.getMessage()));
    }

    @ExceptionHandler(RestorePreviewStaleException.class)
    public ResponseEntity<ErrorResponse> handleRestorePreviewStale(RestorePreviewStaleException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("RESTORE_PREVIEW_STALE", e.getMessage()));
    }

    @ExceptionHandler(ChatSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleChatSessionNotFound(ChatSessionNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("CHAT_SESSION_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ChatSessionLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleChatSessionLimitExceeded(ChatSessionLimitExceededException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CHAT_SESSION_LIMIT_EXCEEDED", e.getMessage()));
    }
}
