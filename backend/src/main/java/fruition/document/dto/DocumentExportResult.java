package fruition.document.dto;

public record DocumentExportResult(
        String filename,
        byte[] bytes
) {
}
