package fruition.core.document.dto;

public record DocumentExportResult(
        String filename,
        byte[] bytes
) {
}
