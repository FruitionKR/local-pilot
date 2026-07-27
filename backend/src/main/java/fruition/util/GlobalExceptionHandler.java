package fruition.util;

import fruition.agent.exception.PipelineAgentException;
import fruition.agent.exception.InvalidAgentTurnRequestException;
import fruition.wikischema.exception.PipelineWikiSchemaException;
import fruition.wikimaintenance.exception.PipelineWikiMaintenanceException;
import fruition.document.exception.DocumentAlreadyProcessingException;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentOriginalNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.exception.DocumentWriteForbiddenException;
import fruition.document.exception.DuplicateDocumentException;
import fruition.document.exception.InvalidDocumentFilenameException;
import fruition.document.exception.InvalidDocumentVersionException;
import fruition.document.exception.InvalidMarkdownContentException;
import fruition.document.exception.MarkdownContentTooLargeException;
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
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.wiki.exception.WikiPageNotFoundException;
import fruition.wiki.exception.WikiPageSlugConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(MarkdownContentTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleMarkdownContentTooLarge(MarkdownContentTooLargeException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("MARKDOWN_CONTENT_TOO_LARGE", e.getMessage()));
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
