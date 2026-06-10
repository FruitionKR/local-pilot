package fruition.poc.backend.document.application;

import fruition.poc.backend.config.StorageProperties;
import fruition.poc.backend.document.domain.Document;
import fruition.poc.backend.document.domain.DocumentUploadException;
import fruition.poc.backend.document.domain.DuplicateDocumentException;
import fruition.poc.backend.document.dto.DocumentUploadResponse;
import fruition.poc.backend.document.infra.DocumentProcessingRequester;
import fruition.poc.backend.document.infra.DocumentRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final DocumentProcessingRequester processingRequester;

    public DocumentService(DocumentRepository documentRepository,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           DocumentProcessingRequester processingRequester) {
        this.documentRepository = documentRepository;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.processingRequester = processingRequester;
    }

    @Transactional
    public DocumentUploadResponse upload(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();

            // 1. content_hash 계산 및 중복 확인
            String contentHash = sha256(bytes);
            documentRepository.findByContentHash(contentHash).ifPresent(existing -> {
                throw new DuplicateDocumentException("이미 업로드된 문서입니다.");
            });

            // 2. MinIO에 원본 파일 저장
            String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String objectPath = "sources/documents/" + documentId + "/original";
            String mimeType = resolveMimeType(file);

            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(storageProps.getBucket())
                                .object(objectPath)
                                .stream(inputStream, bytes.length, -1)
                                .contentType(mimeType)
                                .build()
                );
            }

            // 3. documents 레코드 생성 (status=processing)
            Document document = new Document(
                    documentId,
                    file.getOriginalFilename(),
                    mimeType,
                    file.getSize(),
                    objectPath,
                    contentHash
            );
            documentRepository.save(document);

            // 4. 백그라운드 처리 요청 (실패해도 응답에 영향 없음)
            try {
                processingRequester.request(documentId);
            } catch (Exception e) {
                // 백그라운드 처리 실패는 업로드 응답 실패로 보지 않음
            }

            return new DocumentUploadResponse(
                    document.getId(),
                    document.getFilename(),
                    document.getMimeType(),
                    document.getByteSize(),
                    document.getStatus(),
                    document.getSourceUri(),
                    document.getUploadedAt()
            );

        } catch (DuplicateDocumentException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentUploadException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    private String resolveMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("application/octet-stream")) {
            return contentType;
        }
        String filename = file.getOriginalFilename();
        if (filename != null && filename.endsWith(".md")) {
            return "text/markdown";
        }
        return contentType != null ? contentType : "application/octet-stream";
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}
