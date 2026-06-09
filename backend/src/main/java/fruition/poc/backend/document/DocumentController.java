package fruition.poc.backend.document;

import fruition.poc.backend.common.ErrorResponse;
import fruition.poc.backend.document.dto.DocumentUploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/markdown",
            "text/x-markdown"
    );

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("INVALID_REQUEST", "파일이 없거나 비어 있습니다."));
        }

        String mimeType = file.getContentType();
        boolean isMdByExtension = file.getOriginalFilename() != null
                && file.getOriginalFilename().endsWith(".md");

        if (!ALLOWED_MIME_TYPES.contains(mimeType) && !isMdByExtension) {
            return ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of("UNSUPPORTED_FILE_TYPE", "PDF 또는 Markdown 파일만 업로드할 수 있습니다."));
        }

        // 스텁: DB/MinIO 없이 API 스펙 검증용
        String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        DocumentUploadResponse response = new DocumentUploadResponse(
                documentId,
                file.getOriginalFilename(),
                mimeType != null ? mimeType : "text/markdown",
                file.getSize(),
                DocumentStatus.processing,
                "sources/documents/" + documentId + "/original",
                Instant.now()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
