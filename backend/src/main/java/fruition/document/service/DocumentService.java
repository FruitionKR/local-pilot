package fruition.document.service;

import fruition.util.StorageProperties;
import fruition.document.domain.Document;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentOriginalNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DuplicateDocumentException;
import fruition.document.exception.InvalidDocumentFilenameException;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentOriginalResult;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.DocumentRenameResponse;
import fruition.document.dto.DocumentStatusUpdateRequest;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.DocumentBlockResponse;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentWikiPageRef;
import fruition.document.repository.DocumentProcessingRequester;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceBlockRepository;
import fruition.wiki.domain.DocumentWikiLink;
import fruition.wiki.domain.DocumentWikiRelationType;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final DocumentProcessingRequester processingRequester;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final WikiPageRepository wikiPageRepository;
    private final SourceBlockRepository sourceBlockRepository;

    public DocumentService(DocumentRepository documentRepository,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           DocumentProcessingRequester processingRequester,
                           DocumentWikiLinkRepository documentWikiLinkRepository,
                           WikiPageRepository wikiPageRepository,
                           SourceBlockRepository sourceBlockRepository) {
        this.documentRepository = documentRepository;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.processingRequester = processingRequester;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.sourceBlockRepository = sourceBlockRepository;
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

        List<DocumentWikiLink> links = documentWikiLinkRepository.findAllByIdDocumentId(documentId);
        List<DocumentWikiPageRef> wikiPages = buildWikiPageRefs(links);

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

    private List<DocumentWikiPageRef> buildWikiPageRefs(List<DocumentWikiLink> links) {
        if (links.isEmpty()) return List.of();

        List<String> wikiPageIds = links.stream()
                .map(DocumentWikiLink::getWikiPageId)
                .toList();
        Map<String, WikiPage> pageMap = wikiPageRepository.findAllById(wikiPageIds).stream()
                .collect(Collectors.toMap(WikiPage::getId, p -> p));

        return links.stream()
                .map(link -> {
                    WikiPage page = pageMap.get(link.getWikiPageId());
                    return new DocumentWikiPageRef(
                            link.getWikiPageId(),
                            page != null ? page.getPageType().name() : null,
                            page != null ? page.getTitle() : null,
                            page != null ? page.getSlug() : null,
                            link.getRelationType().name(),
                            link.getConfidence()
                    );
                })
                .toList();
    }

    @Transactional
    public DocumentRenameResponse rename(String documentId, DocumentRenameRequest request) {
        validateFilename(request.filename());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String previousFilename = document.getFilename();
        String newFilename = request.filename().trim();
        document.rename(newFilename);

        Instant renamedAt = Instant.now();
        boolean syncSourceTitle = Boolean.TRUE.equals(request.syncSourceTitle());

        DocumentRenameResponse.SourcePageRef sourcePageRef = buildSourcePageRef(documentId, newFilename, syncSourceTitle);

        return new DocumentRenameResponse(
                document.getId(),
                document.getFilename(),
                previousFilename,
                document.getSourceUri(),
                document.getStatus(),
                renamedAt,
                sourcePageRef
        );
    }

    private DocumentRenameResponse.SourcePageRef buildSourcePageRef(
            String documentId, String newFilename, boolean syncSourceTitle) {
        List<DocumentWikiLink> sourceLinks = documentWikiLinkRepository
                .findAllByIdDocumentIdAndIdRelationType(documentId, DocumentWikiRelationType.source_of);

        if (sourceLinks.isEmpty()) {
            return null;
        }

        String wikiPageId = sourceLinks.get(0).getWikiPageId();
        WikiPage sourcePage = wikiPageRepository.findById(wikiPageId).orElse(null);
        if (sourcePage == null) {
            return null;
        }

        if (syncSourceTitle) {
            String newTitle = stripExtension(newFilename);
            sourcePage.renameTitle(newTitle);
            return new DocumentRenameResponse.SourcePageRef(sourcePage.getId(), newTitle, true);
        }

        return new DocumentRenameResponse.SourcePageRef(sourcePage.getId(), sourcePage.getTitle(), false);
    }

    public DocumentBlocksResponse blocks(String documentId) {
        documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        List<DocumentBlockResponse> blocks = sourceBlockRepository
                .findAllByIdDocumentIdOrderByIdBlockIdAsc(documentId).stream()
                .map(b -> new DocumentBlockResponse(b.getBlockId(), b.getText()))
                .toList();

        return new DocumentBlocksResponse(documentId, blocks);
    }

    public DocumentOriginalResult getOriginal(String documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String objectKey = normalizeObjectKey(document.getSourceUri());
        try {
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectKey)
                            .build());
            return new DocumentOriginalResult(document.getMimeType(), document.getFilename(), stream);
        } catch (Exception e) {
            throw new DocumentOriginalNotFoundException(documentId);
        }
    }

    private String normalizeObjectKey(String sourceUri) {
        String bucketPrefix = "s3://" + storageProps.getBucket() + "/";
        if (sourceUri.startsWith(bucketPrefix)) {
            return sourceUri.substring(bucketPrefix.length());
        }
        if (sourceUri.startsWith("s3://")) {
            int objectStart = sourceUri.indexOf('/', "s3://".length());
            return objectStart >= 0 ? sourceUri.substring(objectStart + 1) : sourceUri;
        }
        return sourceUri;
    }

    private void validateFilename(String filename) {
        if (filename == null) {
            throw new InvalidDocumentFilenameException("문서 이름은 1자 이상 255자 이하여야 합니다.");
        }
        String trimmed = filename.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new InvalidDocumentFilenameException("문서 이름은 1자 이상 255자 이하여야 합니다.");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.indexOf('\0') >= 0) {
            throw new InvalidDocumentFilenameException("문서 이름에 허용되지 않는 문자가 포함되어 있습니다.");
        }
    }

    private String stripExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
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
