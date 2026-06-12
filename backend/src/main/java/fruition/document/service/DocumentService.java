package fruition.document.service;

import fruition.util.StorageProperties;
import fruition.document.domain.Document;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DuplicateDocumentException;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentStatusUpdateRequest;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.DocumentWikiPageRef;
import fruition.document.repository.DocumentProcessingRequester;
import fruition.document.repository.DocumentRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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

            // 4. DB 커밋 이후 백그라운드 처리 요청 (실패해도 업로드 응답에 영향 없음)
            requestProcessingAfterCommit(documentId);

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

    private void requestProcessingAfterCommit(String documentId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            processingRequester.request(documentId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                processingRequester.request(documentId);
            }
        });
    }

    public DocumentListResponse findAll() {
        List<DocumentListResponse.DocumentItem> items = documentRepository.findAll().stream()
                .map(doc -> new DocumentListResponse.DocumentItem(
                        doc.getId(),
                        doc.getFilename(),
                        doc.getMimeType(),
                        doc.getByteSize(),
                        doc.getStatus(),
                        doc.getSourceUri(),
                        doc.getExtractedTextUri(),
                        doc.getUploadedAt(),
                        doc.getProcessedAt(),
                        doc.getErrorMessage()
                ))
                .toList();
        return new DocumentListResponse(items);
    }

    @Transactional
    public void updateStatus(String documentId, DocumentStatusUpdateRequest request) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.updateStatus(
                request.status(),
                request.extractedTextUri(),
                request.processedAt(),
                request.errorMessage()
        );
    }

    public DocumentDetailResponse findById(String documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        // wiki_pages: DocumentWikiLink 구현 전 빈 목록 반환
        List<DocumentWikiPageRef> wikiPages = List.of();
        return new DocumentDetailResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getMimeType(),
                doc.getByteSize(),
                doc.getStatus(),
                doc.getSourceUri(),
                doc.getExtractedTextUri(),
                doc.getUploadedAt(),
                doc.getProcessedAt(),
                doc.getErrorMessage(),
                wikiPages
        );
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
