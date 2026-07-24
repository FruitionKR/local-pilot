package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentRole;
import fruition.document.exception.DocumentUploadException;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class DocumentEditStateInitializer {

    private static final int MAX_MARKDOWN_BYTES = 5 * 1024 * 1024;

    private final DocumentEditStateRepository editStateRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    public DocumentEditStateInitializer(
            DocumentEditStateRepository editStateRepository,
            MinioClient minioClient,
            StorageProperties storageProperties
    ) {
        this.editStateRepository = editStateRepository;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    @Transactional
    public void initializeIfNeeded(Document document) {
        if (document.getDocumentRole() != DocumentRole.EDITABLE
                || document.getSourceUri() == null
                || editStateRepository.existsById(document.getId())) {
            return;
        }
        if (document.getByteSize() > MAX_MARKDOWN_BYTES) {
            throw new DocumentUploadException("편집할 Markdown 본문이 5MB를 초과합니다.", null);
        }

        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(document.getSourceUri())
                        .build()
        )) {
            byte[] bytes = inputStream.readNBytes(MAX_MARKDOWN_BYTES + 1);
            if (bytes.length > MAX_MARKDOWN_BYTES) {
                throw new DocumentUploadException("편집할 Markdown 본문이 5MB를 초과합니다.", null);
            }
            String markdown = new String(bytes, StandardCharsets.UTF_8);
            Instant now = Instant.now();
            editStateRepository.insertIfAbsent(document.getId(), markdown, sha256(bytes), now, now);
        } catch (DocumentUploadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentUploadException("기존 Markdown 편집 상태를 생성하지 못했습니다.", exception);
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
