package fruition.query.exception;

public class PipelineQueryException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    public PipelineQueryException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}
