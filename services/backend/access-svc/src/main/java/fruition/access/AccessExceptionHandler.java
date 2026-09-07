package fruition.access;

import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.EmailVerificationNotFoundException;
import fruition.access.user.exception.EmailVerificationSendException;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.InvalidVerificationCodeException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.OAuthEmailNotProvidedException;
import fruition.access.user.exception.PasswordLoginUnavailableException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.exception.VerificationCodeAttemptsExceededException;
import fruition.access.user.exception.VerificationCodeExpiredException;
import fruition.access.user.exception.VerificationRateLimitedException;
import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import fruition.access.workspace.exception.AlreadyMemberException;
import fruition.access.workspace.exception.InvitationAlreadyAcceptedException;
import fruition.access.workspace.exception.InvitationEmailMismatchException;
import fruition.access.workspace.exception.InvitationExpiredException;
import fruition.access.workspace.exception.InvitationNotFoundException;
import fruition.access.workspace.exception.LastOwnerException;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceMemberNotFoundException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.shared.util.BaseExceptionHandler;
import fruition.shared.util.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** access 앱 전용 예외 매핑. 공통 매핑은 {@link BaseExceptionHandler}에서 상속한다. */
@RestControllerAdvice
public class AccessExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException e) {
        logHandled(e, HttpStatus.CONFLICT, "DUPLICATE_EMAIL");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_EMAIL", e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_CREDENTIALS", e.getMessage()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_REFRESH_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("USER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(WorkspaceNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WORKSPACE_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(WorkspaceMemberNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceMemberNotFound(WorkspaceMemberNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "WORKSPACE_MEMBER_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WORKSPACE_MEMBER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceAccessDenied(WorkspaceAccessDeniedException e) {
        logHandled(e, HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED");
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("WORKSPACE_ACCESS_DENIED", e.getMessage()));
    }

    @ExceptionHandler(LastOwnerException.class)
    public ResponseEntity<ErrorResponse> handleLastOwner(LastOwnerException e) {
        logHandled(e, HttpStatus.CONFLICT, "LAST_OWNER");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("LAST_OWNER", e.getMessage()));
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleInvitationNotFound(InvitationNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("INVITATION_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvitationExpiredException.class)
    public ResponseEntity<ErrorResponse> handleInvitationExpired(InvitationExpiredException e) {
        logHandled(e, HttpStatus.GONE, "INVITATION_EXPIRED");
        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(ErrorResponse.of("INVITATION_EXPIRED", e.getMessage()));
    }

    @ExceptionHandler(InvitationAlreadyAcceptedException.class)
    public ResponseEntity<ErrorResponse> handleInvitationAlreadyAccepted(InvitationAlreadyAcceptedException e) {
        logHandled(e, HttpStatus.CONFLICT, "INVITATION_ALREADY_ACCEPTED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("INVITATION_ALREADY_ACCEPTED", e.getMessage()));
    }

    @ExceptionHandler(InvitationEmailMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvitationEmailMismatch(InvitationEmailMismatchException e) {
        logHandled(e, HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH");
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("INVITATION_EMAIL_MISMATCH", e.getMessage()));
    }

    @ExceptionHandler(AlreadyMemberException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyMember(AlreadyMemberException e) {
        logHandled(e, HttpStatus.CONFLICT, "ALREADY_MEMBER");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ALREADY_MEMBER", e.getMessage()));
    }

    @ExceptionHandler(InvalidOAuthCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthCode(InvalidOAuthCodeException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_OAUTH_CODE");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_OAUTH_CODE", e.getMessage()));
    }

    @ExceptionHandler(OAuthEmailNotProvidedException.class)
    public ResponseEntity<ErrorResponse> handleOAuthEmailNotProvided(OAuthEmailNotProvidedException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "OAUTH_EMAIL_NOT_PROVIDED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("OAUTH_EMAIL_NOT_PROVIDED", e.getMessage()));
    }

    @ExceptionHandler(PasswordLoginUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePasswordLoginUnavailable(PasswordLoginUnavailableException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "PASSWORD_LOGIN_UNAVAILABLE");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("PASSWORD_LOGIN_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(EmailVerificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationNotFound(EmailVerificationNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("VERIFICATION_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidVerificationCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationCode(InvalidVerificationCodeException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_VERIFICATION_CODE", e.getMessage()));
    }

    @ExceptionHandler(VerificationCodeExpiredException.class)
    public ResponseEntity<ErrorResponse> handleVerificationCodeExpired(VerificationCodeExpiredException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_EXPIRED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VERIFICATION_CODE_EXPIRED", e.getMessage()));
    }

    @ExceptionHandler(VerificationCodeAttemptsExceededException.class)
    public ResponseEntity<ErrorResponse> handleVerificationCodeAttemptsExceeded(VerificationCodeAttemptsExceededException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_ATTEMPTS_EXCEEDED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VERIFICATION_CODE_ATTEMPTS_EXCEEDED", e.getMessage()));
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(InvalidVerificationTokenException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_VERIFICATION_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(VerificationRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleVerificationRateLimited(VerificationRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RATE_LIMITED");
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(ErrorResponse.of("VERIFICATION_RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler(EmailAvailabilityRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleEmailAvailabilityRateLimited(EmailAvailabilityRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "EMAIL_AVAILABILITY_RATE_LIMITED");
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(ErrorResponse.of("EMAIL_AVAILABILITY_RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler(EmailVerificationSendException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationSend(EmailVerificationSendException e) {
        logHandled(e, HttpStatus.BAD_GATEWAY, "EMAIL_SEND_FAILED");
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("EMAIL_SEND_FAILED", "인증번호 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
