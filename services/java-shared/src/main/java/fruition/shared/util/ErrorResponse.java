package fruition.shared.util;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ErrorResponse(ErrorDetail error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(String code, String message, List<FieldError> details) {
        public record FieldError(String field, String reason) {}
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message, null));
    }

    public static ErrorResponse ofValidation(List<ErrorDetail.FieldError> details) {
        return new ErrorResponse(new ErrorDetail("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", details));
    }
}
