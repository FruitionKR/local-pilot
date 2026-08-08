package fruition.util;

import fruition.agent.exception.PipelineAgentException;
import fruition.agent.exception.InvalidAgentTurnRequestException;
import fruition.aihistory.exception.InvalidCallbackPayloadException;
import fruition.aihistory.exception.InvalidCallbackTokenException;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.exception.OperationPayloadConflictException;
import fruition.aihistory.exception.OperationNotFoundException;
import fruition.aihistory.exception.RestorePreviewStaleException;
import fruition.aihistory.exception.WikiObjectReadException;
import fruition.wikischema.exception.PipelineWikiSchemaException;
import fruition.wikimaintenance.exception.PipelineWikiMaintenanceException;
import fruition.document.exception.DocumentAlreadyProcessingException;
import fruition.document.exception.DocumentContentVersionNotFoundException;
import fruition.document.exception.DocumentLockedException;
import fruition.document.exception.EditLockLostException;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentAssetNotFoundException;
import fruition.document.exception.DocumentAssetExportException;
import fruition.document.exception.DocumentOriginalNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.exception.DocumentWriteForbiddenException;
import fruition.document.exception.DuplicateDocumentException;
import fruition.document.exception.InvalidDocumentFilenameException;
import fruition.document.exception.InvalidDocumentVersionException;
import fruition.document.exception.InvalidMarkdownContentException;
import fruition.document.exception.InvalidDocumentAssetException;
import fruition.document.exception.DocumentAssetTooLargeException;
import fruition.document.exception.UnsupportedDocumentAssetException;
import fruition.document.exception.DocumentAssetStorageException;
import fruition.document.exception.MarkdownContentTooLargeException;
import fruition.document.exception.MarkdownDiffTooLargeException;
import fruition.document.exception.InvalidIdempotencyKeyException;
import fruition.document.exception.IdempotencyConflictException;
import fruition.document.exception.HierarchyItemNotFoundException;
import fruition.document.exception.HierarchyVersionConflictException;
import fruition.document.exception.HierarchyCycleException;
import fruition.document.exception.InvalidHierarchyRequestException;
import fruition.document.exception.HierarchyWriteForbiddenException;
import fruition.chat.exception.ChatSessionLimitExceededException;
import fruition.chat.exception.ChatSessionNotFoundException;
import fruition.chat.exception.EmptyChatWikiExportException;
import fruition.chat.exception.InvalidChatWikiExportRequestException;
import fruition.query.exception.PipelineQueryException;
import fruition.query.exception.QueryRunNotFoundException;
import fruition.skill.exception.InvalidSkillRequestException;
import fruition.skill.exception.PipelineSkillException;
import fruition.skill.exception.SkillReferenceDocumentNotFoundException;
import fruition.skill.exception.SkillReferenceDocumentTooLargeException;
import fruition.skill.exception.TeamSkillForbiddenException;
import fruition.skill.exception.SkillNotFoundException;
import fruition.skill.exception.SkillConflictException;
import fruition.user.exception.DuplicateEmailException;
import fruition.user.exception.EmailVerificationNotFoundException;
import fruition.user.exception.InvalidCredentialsException;
import fruition.user.exception.InvalidOAuthCodeException;
import fruition.user.exception.InvalidRefreshTokenException;
import fruition.user.exception.InvalidVerificationCodeException;
import fruition.user.exception.InvalidVerificationTokenException;
import fruition.user.exception.OAuthEmailNotProvidedException;
import fruition.user.exception.UserNotFoundException;
import fruition.user.exception.VerificationCodeAttemptsExceededException;
import fruition.user.exception.VerificationCodeExpiredException;
import fruition.user.exception.EmailVerificationSendException;
import fruition.user.exception.VerificationRateLimitedException;
import fruition.wiki.exception.InvalidWikiPageTitleException;
import fruition.wiki.exception.PipelineWikiPageException;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.wiki.exception.WikiPageNotFoundException;
import fruition.wiki.exception.WikiPageSlugConflictException;
import fruition.wiki.exception.WikiPageVersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PipelineSkillException.class)
    public ResponseEntity<?> handlePipelineSkill(PipelineSkillException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return ResponseEntity.status(e.getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBody());
        }
        return ResponseEntity.status(e.getHttpStatus())
                .body(ErrorResponse.of("SKILL_AI_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(InvalidSkillRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSkillRequest(InvalidSkillRequestException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_SKILL_INPUT", e.getMessage()));
    }

    @ExceptionHandler(TeamSkillForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleTeamSkillForbidden(TeamSkillForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("TEAM_SKILL_FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(SkillNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSkillNotFound(SkillNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("SKILL_NOT_FOUND", "Skill을 찾을 수 없습니다."));
    }

    @ExceptionHandler(SkillConflictException.class)
    public ResponseEntity<ErrorResponse> handleSkillConflict(SkillConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(SkillReferenceDocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSkillReferenceNotFound(SkillReferenceDocumentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("REFERENCE_DOCUMENT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(SkillReferenceDocumentTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleSkillReferenceTooLarge(SkillReferenceDocumentTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("REFERENCE_DOCUMENT_TOO_LARGE", e.getMessage()));
    }

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

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartException(MultipartException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "파일이 없거나 비어 있습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.ErrorDetail.FieldError> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.ErrorDetail.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(details));
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

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_IDEMPOTENCY_KEY", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", e.getMessage()));
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

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_EMAIL", e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_CREDENTIALS", e.getMessage()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_REFRESH_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("USER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(WorkspaceNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WORKSPACE_NOT_FOUND", e.getMessage()));
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

    @ExceptionHandler(InvalidOAuthCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthCode(InvalidOAuthCodeException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_OAUTH_CODE", e.getMessage()));
    }

    @ExceptionHandler(OAuthEmailNotProvidedException.class)
    public ResponseEntity<ErrorResponse> handleOAuthEmailNotProvided(OAuthEmailNotProvidedException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("OAUTH_EMAIL_NOT_PROVIDED", e.getMessage()));
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

    @ExceptionHandler(EmailVerificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationNotFound(EmailVerificationNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("VERIFICATION_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidVerificationCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationCode(InvalidVerificationCodeException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_VERIFICATION_CODE", e.getMessage()));
    }

    @ExceptionHandler(VerificationCodeExpiredException.class)
    public ResponseEntity<ErrorResponse> handleVerificationCodeExpired(VerificationCodeExpiredException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VERIFICATION_CODE_EXPIRED", e.getMessage()));
    }

    @ExceptionHandler(VerificationCodeAttemptsExceededException.class)
    public ResponseEntity<ErrorResponse> handleVerificationCodeAttemptsExceeded(VerificationCodeAttemptsExceededException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VERIFICATION_CODE_ATTEMPTS_EXCEEDED", e.getMessage()));
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(InvalidVerificationTokenException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_VERIFICATION_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(VerificationRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleVerificationRateLimited(VerificationRateLimitedException e) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(ErrorResponse.of("VERIFICATION_RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler(EmailVerificationSendException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationSend(EmailVerificationSendException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("EMAIL_SEND_FAILED", "인증번호 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
