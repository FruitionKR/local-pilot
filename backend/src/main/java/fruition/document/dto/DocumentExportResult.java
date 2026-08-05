package fruition.document.dto;

public record DocumentExportResult(
        String filename,
        String contentType,
        byte[] bytes
) {
    public DocumentExportResult(String filename, byte[] bytes) {
        this(filename, "text/markdown;charset=UTF-8", bytes);
    }
}
