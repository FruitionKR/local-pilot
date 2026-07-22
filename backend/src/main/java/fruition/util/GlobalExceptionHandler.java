package fruition.util;

import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentOriginalNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DuplicateDocumentException;
import fruition.document.exception.InvalidDocumentFilenameException;
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
import fruition.user.exception.VerificationRateLimitedException;
import fruition.wiki.exception.InvalidWikiPageTitleException;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.wiki.exception.WikiPageNotFoundException;
import fruition.wiki.exception.WikiPageSlugConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
