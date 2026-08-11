package fruition.shared.util;

import fruition.shared.ai.InvalidAiModelException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyInProgressException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

/**
 * 양쪽 앱(access-svc·document-svc)이 공유하는 공통 예외 매핑.
 * 각 앱의 {@code @RestControllerAdvice}가 이 클래스를 상속해 앱별 예외 매핑을 더한다.
 */
public abstract class BaseExceptionHandler {

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
                .body(ErrorResponse.of("IDEMPOTENCY_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(IdempotencyInProgressException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_IN_PROGRESS", e.getMessage()));
    }

    @ExceptionHandler(InvalidAiModelException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAiModel(InvalidAiModelException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_AI_MODEL", e.getMessage()));
    }
}
