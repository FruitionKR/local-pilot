package fruition.document.service;

import fruition.util.StorageProperties;
import fruition.document.domain.Document;
import fruition.document.domain.DocumentProcessingState;
import fruition.document.domain.DocumentStatus;
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
import fruition.document.domain.DocumentProcessingQueue;
import fruition.document.repository.DocumentProcessingQueueRepository;
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
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int STALLED_THRESHOLD_SECONDS = 60;

    private final DocumentRepository documentRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final DocumentProcessingRequester processingRequester;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final WikiPageRepository wikiPageRepository;
    private final SourceBlockRepository sourceBlockRepository;
    private final DocumentProcessingQueueRepository queueRepository;
    private final TransactionTemplate transactionTemplate;
    private final String callbackBaseUrl;

    public DocumentService(DocumentRepository documentRepository,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           DocumentProcessingRequester processingRequester,
                           DocumentWikiLinkRepository documentWikiLinkRepository,
                           WikiPageRepository wikiPageRepository,
                           SourceBlockRepository sourceBlockRepository,
                           DocumentProcessingQueueRepository queueRepository,
                           TransactionTemplate transactionTemplate,
                           @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.documentRepository = documentRepository;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.processingRequester = processingRequester;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.sourceBlockRepository = sourceBlockRepository;
        this.queueRepository = queueRepository;
        this.transactionTemplate = transactionTemplate;
        this.callbackBaseUrl = callbackBaseUrl;
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
            transactionTemplate.execute(status -> {
                queueRepository.save(new DocumentProcessingQueue(documentId));
                return null;
            });
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                transactionTemplate.execute(status -> {
                    queueRepository.save(new DocumentProcessingQueue(documentId));
                    return null;
                });
            }
        });
    }

    void doRequestProcessing(String documentId) {
        String callbackUrl = callbackBaseUrl + "/api/documents/" + documentId + "/pipeline-events";
        try {
            DocumentProcessingRequester.PipelineRunResponse response =
                    processingRequester.request(documentId, callbackUrl);
            String runId = response != null ? response.runId() : null;
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findById(documentId).ifPresent(doc -> doc.markPipelineStarted(runId, now));
                return null;
            });
        } catch (Exception e) {
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findById(documentId).ifPresent(doc ->
                        doc.markProcessingFailed("Pipeline run request failed: " + e.getMessage(), now));
                return null;
            });
        }
    }

    @Transactional
    public void applyPipelineEvent(String documentId, String runId) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            if (runId != null && !runId.equals(doc.getPipelineRunId())) {
                return;
            }
            doc.markProcessingHeartbeat(Instant.now());
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
                        doc.getErrorMessage(),
                        doc.getPipelineRunId(),
                        resolveProcessingState(doc)
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
                wikiPages,
                doc.getPipelineRunId(),
                resolveProcessingState(doc)
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

    @Transactional
    public void delete(String documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String sourceUri = document.getSourceUri();
        String extractedTextUri = document.getExtractedTextUri();

        // 처리 queue에서 제거
        queueRepository.deleteByDocumentId(documentId);

        // source wiki page 삭제 → wiki_page_links, wiki_page_embeddings CASCADE
        wikiPageRepository.findById("source:" + documentId)
                .ifPresent(wikiPageRepository::delete);

        // document 삭제 → source_blocks, document_wiki_links, wiki_embedding_units CASCADE
        documentRepository.delete(document);

        // commit 이후 MinIO 오브젝트 삭제
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteMinioObject(sourceUri);
                if (extractedTextUri != null) {
                    deleteMinioObject(extractedTextUri);
                }
            }
        });
    }

    private void deleteMinioObject(String uri) {
        if (uri == null || uri.isBlank()) return;
        String objectKey = normalizeObjectKey(uri);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[MinIO 오브젝트 삭제 실패] uri={} error={}", uri, e.getMessage());
        }
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

    private DocumentProcessingState resolveProcessingState(Document doc) {
        if (doc.getStatus() == DocumentStatus.completed) return DocumentProcessingState.completed;
        if (doc.getStatus() == DocumentStatus.failed) return DocumentProcessingState.failed;
        if (doc.getPipelineRunId() == null) return DocumentProcessingState.starting;
        if (doc.getProcessingUpdatedAt() == null) return DocumentProcessingState.starting;
        boolean stalled = doc.getProcessingUpdatedAt()
                .isBefore(Instant.now().minusSeconds(STALLED_THRESHOLD_SECONDS));
        return stalled ? DocumentProcessingState.stalled : DocumentProcessingState.running;
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
