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
import fruition.wiki.repository.WikiPageLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int STALLED_THRESHOLD_SECONDS = 60;
    private static final String INITIAL_NOTE_FILENAME = "새 노트.md";

    private final DocumentRepository documentRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final DocumentProcessingRequester processingRequester;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final SourceBlockRepository sourceBlockRepository;
    private final DocumentProcessingQueueRepository queueRepository;
    private final TransactionTemplate transactionTemplate;
    private final DocumentEditStateInitializer editStateInitializer;
    private final String callbackBaseUrl;

    public DocumentService(DocumentRepository documentRepository,
                           WorkspaceMemberRepository workspaceMemberRepository,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           DocumentProcessingRequester processingRequester,
                           DocumentWikiLinkRepository documentWikiLinkRepository,
                           WikiPageRepository wikiPageRepository,
                           WikiPageLinkRepository wikiPageLinkRepository,
                           SourceBlockRepository sourceBlockRepository,
                           DocumentProcessingQueueRepository queueRepository,
                           TransactionTemplate transactionTemplate,
                           DocumentEditStateInitializer editStateInitializer,
                           @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.documentRepository = documentRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.processingRequester = processingRequester;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.wikiPageLinkRepository = wikiPageLinkRepository;
        this.sourceBlockRepository = sourceBlockRepository;
        this.queueRepository = queueRepository;
        this.transactionTemplate = transactionTemplate;
        this.editStateInitializer = editStateInitializer;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    @Transactional
    public DocumentUploadResponse upload(String workspaceId, String userId, MultipartFile file) {
        verifyWorkspaceOwnership(workspaceId, userId);
        try {
            byte[] bytes = file.getBytes();
            log.info("[문서 업로드 요청] workspaceId={} userId={} filename={} contentType={} size={}",
                    workspaceId, userId, file.getOriginalFilename(), file.getContentType(), file.getSize());

            // 1. content_hash 계산 및 중복 확인
            String contentHash = sha256(bytes);
            Optional<Document> duplicate = documentRepository.findByWorkspaceIdAndContentHash(workspaceId, contentHash);
            log.info("[문서 중복 확인] contentHashPrefix={} duplicate={}",
                    contentHashPrefix(contentHash), duplicate.isPresent());
            duplicate.ifPresent(existing -> {
                log.warn("[문서 중복 감지] documentId={} contentHashPrefix={}",
                        existing.getId(), contentHashPrefix(contentHash));
                throw new DuplicateDocumentException("이미 업로드된 문서입니다.");
            });

            // 2. MinIO에 원본 파일 저장
            String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
            String objectPath = "sources/documents/" + documentId + "/original";
            String mimeType = resolveMimeType(file);
            log.info("[문서 원본 저장 시작] documentId={} bucket={} objectPath={} mimeType={} byteSize={}",
                    documentId, storageProps.getBucket(), objectPath, mimeType, bytes.length);

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
            log.info("[문서 원본 저장 완료] documentId={} objectPath={}", documentId, objectPath);

            // 3. documents 레코드 생성 (status=processing)
            Document document = new Document(
                    documentId,
                    workspaceId,
                    userId,
                    file.getOriginalFilename(),
                    mimeType,
                    file.getSize(),
                    objectPath,
                    contentHash
            );
            documentRepository.save(document);
            log.info("[문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} status={} sourceUri={}",
                    document.getId(), document.getWorkspaceId(), document.getUserId(),
                    document.getFilename(), document.getStatus(), document.getSourceUri());

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

    /**
     * 새 워크스페이스에 표시할 초기 Markdown 노트를 저장한다.
     * 노트는 원본 조회(getOriginal)만으로 열람 가능하므로 파이프라인 처리 큐에는 올리지 않는다.
     * 실패해도 워크스페이스 생성을 막지 않도록 예외를 삼키고 로그만 남긴다(best-effort).
     */
    @Transactional
    public void createInitialNote(String workspaceId, String userId) {
        // content_hash 중복 판별이 (workspace_id, content_hash) 범위로 바뀐 뒤로(V5),
        // 워크스페이스마다 해시를 다르게 만들던 fruition-workspace 식별 주석은 필요 없다.
        String markdown = "# 새 노트\n";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        String objectPath = "sources/documents/" + documentId + "/original";

        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectPath)
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );

            Document document = new Document(
                    documentId,
                    workspaceId,
                    userId,
                    INITIAL_NOTE_FILENAME,
                    "text/markdown",
                    bytes.length,
                    objectPath,
                    sha256(bytes)
            );
            // 초기 노트는 파이프라인 처리 대상이 아니므로(큐에 올리지 않음) 곧바로 completed로 둔다.
            document.updateStatus(DocumentStatus.completed, null, Instant.now(), null);
            documentRepository.save(document);
        } catch (Exception e) {
            log.warn("초기 노트 저장 실패로 건너뜁니다. workspaceId={}", workspaceId, e);
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
            log.info("[문서 처리 큐 즉시 등록] documentId={} transactionActive=false", documentId);
            transactionTemplate.execute(status -> {
                queueRepository.save(new DocumentProcessingQueue(documentId));
                log.info("[문서 처리 큐 등록 완료] documentId={} status=pending", documentId);
                return null;
            });
            return;
        }
        log.info("[문서 처리 큐 등록 예약] documentId={} afterCommit=true", documentId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                transactionTemplate.execute(status -> {
                    queueRepository.save(new DocumentProcessingQueue(documentId));
                    log.info("[문서 처리 큐 등록 완료] documentId={} status=pending", documentId);
                    return null;
                });
            }
        });
    }

    /** 채팅 Wiki page화 export 결과. skipped=true면 동일 content가 이미 존재해 새로 만들지 않았다. */
    public record ExportDocumentResult(String documentId, boolean skipped) {}

    /**
     * 채팅 export Markdown을 문서로 저장하고 처리 큐에 등록한다. (권한 검증은 호출부에서 이미 수행)
     * contentHash로 중복을 확인해, 이미 있으면 기존 문서 id로 skipped 결과를 반환한다.
     */
    @Transactional
    public ExportDocumentResult createChatExportDocument(String workspaceId, String userId,
                                                         String filename, String markdown, String contentHash,
                                                         String selectionMode) {
        if (selectionMode == null || selectionMode.isBlank()) {
            throw new IllegalArgumentException("채팅 export 문서는 selection_mode가 필요합니다.");
        }
        Optional<Document> existing = documentRepository.findByWorkspaceIdAndContentHash(workspaceId, contentHash);
        if (existing.isPresent()) {
            return new ExportDocumentResult(existing.get().getId(), true);
        }

        String documentId = "chatdoc_" + UUID.randomUUID().toString().replace("-", "");
        String objectPath = "sources/documents/" + documentId + "/original";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(objectPath)
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );
        } catch (Exception e) {
            throw new DocumentUploadException("채팅 export 저장 중 오류가 발생했습니다.", e);
        }

        Document document = new Document(
                documentId, workspaceId, userId, filename, "text/markdown", bytes.length,
                objectPath, contentHash, "chat_export");
        document.assignSelectionMode(selectionMode);
        documentRepository.save(document);
        log.info("[채팅 export 문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} selectionMode={} status={} sourceUri={}",
                document.getId(), document.getWorkspaceId(), document.getUserId(), document.getFilename(),
                document.getSelectionMode(), document.getStatus(), document.getSourceUri());

        requestProcessingAfterCommit(documentId);

        return new ExportDocumentResult(documentId, false);
    }

    /**
     * 채팅 full 재생성: 기존 export 문서(documentId)를 재사용한다. MinIO 원본을 세션 전체(fullMarkdown)로 덮어쓰고,
     * 파이프라인엔 미편입 문답(deltaMarkdown)만 inline으로 보내도록 문서를 갱신한 뒤 처리 큐에 재등록한다.
     */
    @Transactional
    public void regenerateChatExportDocument(String documentId, String fullMarkdown, String fullContentHash,
                                             String deltaMarkdown) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        byte[] bytes = fullMarkdown.getBytes(StandardCharsets.UTF_8);
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(document.getSourceUri())
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );
        } catch (Exception e) {
            throw new DocumentUploadException("채팅 export 재생성 저장 중 오류가 발생했습니다.", e);
        }

        document.reopenForChatExportRegeneration(fullContentHash, bytes.length, deltaMarkdown);
        log.info("[채팅 export 재생성 DB 갱신 완료] documentId={} contentHashPrefix={} byteSize={} deltaMarkdownLength={}",
                documentId, contentHashPrefix(fullContentHash), bytes.length,
                deltaMarkdown != null ? deltaMarkdown.length() : 0);
        requestProcessingAfterCommit(documentId);
    }

    void doRequestProcessing(String documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("[문서 처리 요청 생략] documentId={} reason=document_not_found", documentId);
            return;
        }
        String callbackUrl = callbackBaseUrl + "/api/documents/" + documentId + "/pipeline-events";
        boolean chatWiki = "chat_export".equals(document.getOrigin());
        log.info("[문서 처리 요청 시작] documentId={} origin={} chatWiki={} workspaceId={} userId={} callbackUrl={} selectionMode={} inputMarkdownPresent={} inputMarkdownLength={}",
                documentId, document.getOrigin(), chatWiki, document.getWorkspaceId(), document.getUserId(),
                callbackUrl, document.getSelectionMode(), document.getPipelineInputMarkdown() != null,
                document.getPipelineInputMarkdown() != null ? document.getPipelineInputMarkdown().length() : 0);
        try {
            DocumentProcessingRequester.PipelineRunResponse response =
                    processingRequester.request(documentId, document.getUserId(), document.getWorkspaceId(),
                            callbackUrl, document.getSelectionMode(), document.getPipelineInputMarkdown(), chatWiki);
            String runId = response != null ? response.runId() : null;
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findById(documentId).ifPresent(doc -> doc.markPipelineStarted(runId, now));
                return null;
            });
            log.info("[문서 처리 run 기록 완료] documentId={} runId={}", documentId, runId);
        } catch (Exception e) {
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findById(documentId).ifPresent(doc ->
                        doc.markProcessingFailed("Pipeline run request failed: " + e.getMessage(), now));
                return null;
            });
            log.warn("[문서 처리 요청 실패 반영] documentId={} error={}", documentId, e.getMessage());
        }
    }

    @Transactional
    public void applyPipelineEvent(String documentId, String runId, String stage,
                                   String message, Map<String, Object> data) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            log.info("[파이프라인 이벤트 수신] documentId={} runId={} stage={} message={} dataKeys={}",
                    documentId, runId, stage, message, data != null ? data.keySet() : List.of());
            if (runId != null && !runId.equals(doc.getPipelineRunId())) {
                log.warn("[파이프라인 이벤트 무시] documentId={} requestRunId={} currentRunId={} stage={}",
                        documentId, runId, doc.getPipelineRunId(), stage);
                return;
            }
            doc.markProcessingHeartbeat(stage, Instant.now());
            log.info("[문서 처리 heartbeat 반영] documentId={} runId={} stage={}", documentId, runId, stage);
        });
    }

    private String contentHashPrefix(String contentHash) {
        if (contentHash == null) return null;
        return contentHash.substring(0, Math.min(contentHash.length(), 16));
    }

    public DocumentListResponse findAll(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);

        List<DocumentListResponse.DocumentItem> items = documentRepository.findVisibleByWorkspaceId(workspaceId).stream()
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
                        resolveProcessingState(doc),
                        doc.getProcessingStage()
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

    @Transactional
    public DocumentDetailResponse findById(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document doc = documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        editStateInitializer.initializeIfNeeded(doc);

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
                resolveProcessingState(doc),
                doc.getProcessingStage()
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
    public DocumentRenameResponse rename(String workspaceId, String userId, String documentId, DocumentRenameRequest request) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateFilename(request.filename());

        Document document = documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
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
    public void delete(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        deleteInternal(document);
    }

    /** 워크스페이스 삭제 시 소속 문서를 함께 정리한다. DB에 workspace_id FK CASCADE가 없어 애플리케이션에서 직접 처리한다. */
    @Transactional
    public void deleteAllByWorkspaceId(String workspaceId) {
        documentRepository.findAllByWorkspaceId(workspaceId).forEach(this::deleteInternal);
    }

    private void deleteInternal(Document document) {
        String documentId = document.getId();
        String sourceUri = document.getSourceUri();
        String extractedTextUri = document.getExtractedTextUri();

        // 처리 queue에서 제거
        queueRepository.deleteByDocumentId(documentId);

        // 이 문서의 source wiki page와 그에 딸린 링크를 명시적으로 삭제한다.
        // - wiki page id는 opaque UUID이므로 id 문자열 형식이 아니라 document_wiki_links(source_of)로 찾는다.
        // - wiki_page_links는 link_type이 아니라 삭제되는 source page id(from/to)로 좁혀 지운다.
        // - concept page는 여러 문서가 공유하므로 삭제하지 않는다.
        documentWikiLinkRepository
                .findAllByIdDocumentIdAndIdRelationType(documentId, DocumentWikiRelationType.source_of)
                .forEach(link -> {
                    String sourcePageId = link.getWikiPageId();
                    wikiPageLinkRepository.deleteByIdFromPageIdOrIdToPageId(sourcePageId, sourcePageId);
                    wikiPageRepository.findById(sourcePageId).ifPresent(wikiPageRepository::delete);
                });

        // document에 종속된 나머지 데이터를 명시적으로 삭제한다.
        // 이 테이블들은 DB에 ON DELETE CASCADE FK가 없어(Spring이 생성) document 삭제만으로는 정리되지 않는다.
        documentWikiLinkRepository.deleteByIdDocumentId(documentId);
        sourceBlockRepository.deleteByIdDocumentId(documentId);

        // document 삭제
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

    public DocumentBlocksResponse blocks(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        List<DocumentBlockResponse> blocks = sourceBlockRepository
                .findAllByIdDocumentIdOrderByIdBlockIdAsc(documentId).stream()
                .map(b -> new DocumentBlockResponse(b.getBlockId(), b.getText()))
                .toList();

        return new DocumentBlocksResponse(documentId, blocks);
    }

    public DocumentOriginalResult getOriginal(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
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
