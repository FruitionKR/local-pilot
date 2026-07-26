package fruition.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.util.StorageProperties;
import fruition.document.domain.Document;
import fruition.document.domain.DocumentContentVersion;
import fruition.document.domain.DocumentContentVersionId;
import fruition.document.domain.DocumentEditState;
import fruition.document.domain.DocumentProcessingState;
import fruition.document.domain.DocumentRole;
import fruition.document.domain.DocumentStatus;
import fruition.document.domain.IdempotencyRecord;
import fruition.document.exception.DocumentContentVersionNotFoundException;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentOriginalNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DocumentAlreadyProcessingException;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.exception.DocumentWriteForbiddenException;
import fruition.document.exception.InvalidDocumentFilenameException;
import fruition.document.exception.InvalidDocumentVersionException;
import fruition.document.exception.InvalidIdempotencyKeyException;
import fruition.document.exception.IdempotencyConflictException;
import fruition.document.exception.InvalidMarkdownContentException;
import fruition.document.exception.MarkdownContentTooLargeException;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentContentSaveResponse;
import fruition.document.dto.DocumentContentVersionListResponse;
import fruition.document.dto.DocumentContentVersionResponse;
import fruition.document.dto.DocumentIngestResponse;
import fruition.document.dto.DocumentDuplicateResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentLifecycleRequest;
import fruition.document.dto.DocumentLifecycleResponse;
import fruition.document.dto.MarkdownDocumentCreateRequest;
import fruition.document.dto.DocumentOriginalResult;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.DocumentRenameResponse;
import fruition.document.dto.DocumentStatusUpdateRequest;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.DocumentTrashResponse;
import fruition.document.dto.DocumentBlockResponse;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentWikiPageRef;
import fruition.document.domain.DocumentProcessingQueue;
import fruition.document.repository.DocumentProcessingQueueRepository;
import fruition.document.repository.DocumentProcessingRequester;
import fruition.document.repository.DocumentContentVersionRepository;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.repository.SourceFolderRepository;
import fruition.document.repository.IdempotencyRecordRepository;
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
import java.util.Set;
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
    private final DocumentEditStateRepository editStateRepository;
    private final DocumentContentVersionRepository contentVersionRepository;
    private final SourceFolderRepository sourceFolderRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
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
                           DocumentEditStateRepository editStateRepository,
                           DocumentContentVersionRepository contentVersionRepository,
                           SourceFolderRepository sourceFolderRepository,
                           IdempotencyRecordRepository idempotencyRecordRepository,
                           ObjectMapper objectMapper,
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
        this.editStateRepository = editStateRepository;
        this.contentVersionRepository = contentVersionRepository;
        this.sourceFolderRepository = sourceFolderRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    @Transactional
    public DocumentUploadResponse upload(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MultipartFile file
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        String objectPath = null;
        boolean objectStored = false;
        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            validateFilename(filename);
            String mimeType = resolveMimeType(file);
            boolean markdownUpload = isMarkdown(filename, mimeType);
            DocumentEditingRules.MarkdownContent markdownContent =
                    markdownUpload ? DocumentEditingRules.markdown(bytes) : null;
            String contentHash = markdownUpload ? markdownContent.contentHash() : sha256(bytes);
            String endpointScope = uploadEndpointScope(workspaceId);
            String requestHash = requestHash(filename.trim(), mimeType, contentHash);

            Optional<DocumentUploadResponse> replay = replayIdempotentRequest(
                    userId, endpointScope, idempotencyKey, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }

            log.info("[문서 업로드 요청] workspaceId={} userId={} filename={} contentType={} size={}",
                    workspaceId, userId, file.getOriginalFilename(), file.getContentType(), file.getSize());

            String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
            objectPath = "sources/documents/" + documentId + "/original";
            log.info("[문서 원본 저장 시작] documentId={} bucket={} objectPath={} mimeType={} byteSize={}",
                    documentId, storageProps.getBucket(), objectPath, mimeType, bytes.length);

            try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(storageProps.getBucket())
                                .object(objectPath)
                                .stream(inputStream, bytes.length, -1)
                                .contentType(mimeType)
                                .build()
                );
            }
            objectStored = true;
            registerMinioRollbackCleanup(objectPath);
            log.info("[문서 원본 저장 완료] documentId={} objectPath={}", documentId, objectPath);

            Document document = new Document(
                    documentId,
                    workspaceId,
                    userId,
                    filename.trim(),
                    mimeType,
                    bytes.length,
                    objectPath,
                    contentHash
            );
            document.placeAtRoot(nextRootSortOrder(workspaceId, document.getDocumentRole()));
            if (!markdownUpload) {
                document.updateStatus(DocumentStatus.uploaded, null, null, null);
            }
            documentRepository.save(document);
            if (markdownUpload) {
                editStateRepository.save(new DocumentEditState(
                        documentId, markdownContent.markdown(), markdownContent.contentHash()));
            }
            DocumentUploadResponse response = toUploadResponse(document, markdownUpload);
            saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
            log.info("[문서 DB 저장 완료] documentId={} workspaceId={} userId={} filename={} status={} sourceUri={}",
                    document.getId(), document.getWorkspaceId(), document.getUserId(),
                    document.getFilename(), document.getStatus(), document.getSourceUri());

            if (markdownUpload) {
                requestProcessingAfterCommit(documentId);
            }
            return response;
        } catch (InvalidDocumentFilenameException
                 | InvalidIdempotencyKeyException
                 | IdempotencyConflictException
                 | InvalidMarkdownContentException
                 | MarkdownContentTooLargeException e) {
            throw e;
        } catch (Exception e) {
            if (objectStored) {
                deleteMinioObject(objectPath);
            }
            throw new DocumentUploadException("파일 저장 중 오류가 발생했습니다.", e);
        }
    }

    @Transactional
    public DocumentUploadResponse createMarkdown(
            String workspaceId,
            String userId,
            String idempotencyKey,
            MarkdownDocumentCreateRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new InvalidMarkdownContentException("Markdown 생성 요청은 필수입니다.");
        }

        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(request.displayName(), "document.md");
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(request.markdown());
        String endpointScope = markdownEndpointScope(workspaceId);
        String requestHash = requestHash(filename.filename(), "text/markdown", content.contentHash());

        Optional<DocumentUploadResponse> replay =
                replayIdempotentRequest(userId, endpointScope, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        DocumentUploadResponse response =
                createMarkdownDocument(workspaceId, userId, filename.filename(), content, "direct");
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    @Transactional
    public DocumentDuplicateResponse duplicate(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        // 원본 행을 비관적 잠금으로 조회해 같은 문서에 대한 동시 복제 요청을 직렬화한다(멱등 레코드 경쟁 방지).
        Document source = documentRepository.findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .filter(document -> document.getDeletedAt() == null)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(source, userId);
        if (source.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new DocumentWriteForbiddenException("편집 가능한 Markdown 문서만 복제할 수 있습니다.");
        }

        editStateInitializer.initializeIfNeeded(source);
        DocumentEditState sourceEditState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new DocumentWriteForbiddenException(
                        "최신 Markdown 편집본이 있는 문서만 복제할 수 있습니다."));
        String endpointScope = duplicateEndpointScope(workspaceId);
        String requestHash = requestHash(documentId, "duplicate", "");
        Optional<DocumentDuplicateResponse> replay = replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentDuplicateResponse.class, this::toDuplicateResponse);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 복제본은 원본과 같은 폴더에 붙인다(원본이 root면 root). 이름 충돌은 워크스페이스 전체 기준으로 회피한다.
        Set<String> existingNames = documentRepository.findVisibleByWorkspaceId(workspaceId).stream()
                .map(Document::getNormalizedFilename)
                .collect(Collectors.toSet());
        DocumentEditingRules.Filename duplicateFilename =
                DocumentEditingRules.duplicateFilename(source.getDisplayName(), existingNames);
        long sortOrder = documentRepository.findMaxSortOrderInFolder(
                workspaceId, source.getSourceFolderId()) + 1;
        DocumentEditingRules.MarkdownContent content =
                DocumentEditingRules.markdown(sourceEditState.getMarkdown());

        String duplicateId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        Document duplicate = new Document(
                duplicateId,
                workspaceId,
                userId,
                duplicateFilename.filename(),
                "text/markdown",
                content.bytes().length,
                null,
                null,
                "duplicate"
        );
        duplicate.initializeDuplicate(
                source.getId(),
                content.contentHash(),
                content.bytes().length,
                sortOrder,
                source.getSourceFolderId()
        );
        documentRepository.save(duplicate);
        editStateRepository.save(new DocumentEditState(
                duplicateId, content.markdown(), content.contentHash()));

        DocumentDuplicateResponse response = toDuplicateResponse(duplicate);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    /** 새 워크스페이스에 직접 생성 Markdown 노트를 저장한다. 실패는 기존처럼 best-effort로 처리한다. */
    @Transactional
    public void createInitialNote(String workspaceId, String userId) {
        try {
            createMarkdownDocument(
                    workspaceId,
                    userId,
                    INITIAL_NOTE_FILENAME,
                    DocumentEditingRules.markdown("# 새 노트\n"),
                    "direct"
            );
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
        if (filename != null) {
            String normalizedFilename = filename.toLowerCase(java.util.Locale.ROOT);
            if (normalizedFilename.endsWith(".md") || normalizedFilename.endsWith(".markdown")) {
                return "text/markdown";
            }
        }
        return contentType != null ? contentType : "application/octet-stream";
    }

    private DocumentUploadResponse createMarkdownDocument(
            String workspaceId,
            String userId,
            String filename,
            DocumentEditingRules.MarkdownContent content,
            String origin
    ) {
        String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        Document document = new Document(
                documentId,
                workspaceId,
                userId,
                filename,
                "text/markdown",
                content.bytes().length,
                null,
                null,
                origin
        );
        document.initializeDirectMarkdown(
                content.contentHash(),
                content.bytes().length,
                nextRootSortOrder(workspaceId, DocumentRole.EDITABLE)
        );
        documentRepository.save(document);
        editStateRepository.save(new DocumentEditState(
                documentId, content.markdown(), content.contentHash()));
        return toUploadResponse(document, true);
    }

    private long nextRootSortOrder(String workspaceId, DocumentRole documentRole) {
        return documentRepository.findMaxRootSortOrder(workspaceId, documentRole) + 1;
    }

    private boolean isMarkdown(String filename, String mimeType) {
        String normalizedFilename = filename.toLowerCase(java.util.Locale.ROOT);
        return "text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType)
                || normalizedFilename.endsWith(".md")
                || normalizedFilename.endsWith(".markdown");
    }

    private DocumentUploadResponse toUploadResponse(Document document, boolean editable) {
        return new DocumentUploadResponse(
                document.getId(),
                document.getFilename(),
                document.getMimeType(),
                document.getByteSize(),
                document.getStatus(),
                document.getSourceUri(),
                document.getUploadedAt(),
                editable,
                document.getCurrentVersion(),
                document.getDocumentRole()
        );
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key는 1자 이상 255자 이하여야 합니다.");
        }
    }

    private <T> Optional<T> replayIdempotentRequest(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            Class<T> responseType,
            java.util.function.Function<Document, T> fallback
    ) {
        Optional<IdempotencyRecord> found =
                idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                        userId, endpointScope, idempotencyKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecord record = found.get();
        if (!record.getExpiresAt().isAfter(Instant.now())) {
            idempotencyRecordRepository.delete(record);
            idempotencyRecordRepository.flush();
            return Optional.empty();
        }
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.");
        }

        if (record.getResponseBody() != null) {
            try {
                return Optional.of(objectMapper.readValue(
                        record.getResponseBody(), responseType));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("멱등성 응답을 복원할 수 없습니다.", exception);
            }
        }

        Document document = documentRepository.findById(record.getResourceId())
                .orElseThrow(() -> new IdempotencyConflictException(
                        "멱등성 기록의 기존 문서를 찾을 수 없습니다."));
        return Optional.of(fallback.apply(document));
    }

    private Optional<DocumentUploadResponse> replayIdempotentRequest(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash
    ) {
        return replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentUploadResponse.class,
                document -> toUploadResponse(
                        document, editStateRepository.existsById(document.getId())));
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            Object response,
            String resourceId
    ) {
        Instant now = Instant.now();
        String responseBody;
        try {
            responseBody = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("멱등성 응답을 저장할 수 없습니다.", exception);
        }
        idempotencyRecordRepository.save(new IdempotencyRecord(
                UUID.randomUUID(),
                userId,
                endpointScope,
                idempotencyKey,
                requestHash,
                201,
                resourceId,
                responseBody,
                now,
                now.plusSeconds(24 * 60 * 60)
        ));
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            DocumentLifecycleResponse response
    ) {
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response, response.id());
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            DocumentUploadResponse response
    ) {
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response, response.id());
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            DocumentDuplicateResponse response
    ) {
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response, response.id());
    }

    private String uploadEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents";
    }

    private String markdownEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/markdown";
    }

    private String duplicateEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/duplicate";
    }

    private DocumentDuplicateResponse toDuplicateResponse(Document document) {
        return new DocumentDuplicateResponse(
                document.getId(),
                document.getFilename(),
                document.getDisplayName(),
                document.getMimeType(),
                document.getByteSize(),
                document.getCurrentVersion(),
                document.getSourceDocumentId(),
                document.getSortOrder()
        );
    }

    private String requestHash(String filename, String mimeType, String contentHash) {
        return sha256((filename + "\0" + mimeType + "\0" + contentHash)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void registerMinioRollbackCleanup(String objectPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteMinioObject(objectPath);
                }
            }
        });
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
        Document document = documentRepository.findByIdInActiveWorkspace(documentId).orElse(null);
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
                    processingRequester.request(documentId, callbackUrl,
                            document.getSelectionMode(), document.getPipelineInputMarkdown(), chatWiki);
            String runId = response != null ? response.runId() : null;
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findByIdInActiveWorkspace(documentId)
                        .ifPresent(doc -> doc.markPipelineStarted(runId, now));
                return null;
            });
            log.info("[문서 처리 run 기록 완료] documentId={} runId={}", documentId, runId);
        } catch (Exception e) {
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findByIdInActiveWorkspace(documentId).ifPresent(doc ->
                        doc.markProcessingFailed("Pipeline run request failed: " + e.getMessage(), now));
                return null;
            });
            log.warn("[문서 처리 요청 실패 반영] documentId={} error={}", documentId, e.getMessage());
        }
    }

    @Transactional
    public void applyPipelineEvent(String documentId, String runId, String stage,
                                   String message, Map<String, Object> data) {
        Document doc = documentRepository.findByIdInActiveWorkspace(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        log.info("[파이프라인 이벤트 수신] documentId={} runId={} stage={} message={} dataKeys={}",
                documentId, runId, stage, message, data != null ? data.keySet() : List.of());
        if (runId != null && !runId.equals(doc.getPipelineRunId())) {
            log.warn("[파이프라인 이벤트 무시] documentId={} requestRunId={} currentRunId={} stage={}",
                    documentId, runId, doc.getPipelineRunId(), stage);
            return;
        }
        doc.markProcessingHeartbeat(stage, Instant.now());
        log.info("[문서 처리 heartbeat 반영] documentId={} runId={} stage={}", documentId, runId, stage);
    }

    private String contentHashPrefix(String contentHash) {
        if (contentHash == null) return null;
        return contentHash.substring(0, Math.min(contentHash.length(), 16));
    }

    public DocumentListResponse findAll(String workspaceId, String userId, String query) {
        verifyWorkspaceOwnership(workspaceId, userId);

        List<Document> documents = query == null || query.isBlank()
                ? documentRepository.findVisibleByWorkspaceId(workspaceId)
                : documentRepository.searchVisibleByWorkspaceId(workspaceId, query.trim());
        Set<String> editableDocumentIds = editStateRepository.findAllById(
                        documents.stream().map(Document::getId).toList()).stream()
                .map(DocumentEditState::getDocumentId)
                .collect(Collectors.toSet());

        List<DocumentListResponse.DocumentItem> items = documents.stream()
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
                        doc.getProcessingStage(),
                        areaOf(doc),
                        itemKindOf(doc),
                        doc.getDisplayName(),
                        fileTypeOf(doc),
                        doc.getDocumentRole(),
                        isEditable(doc, editableDocumentIds.contains(doc.getId())),
                        doc.getCurrentVersion(),
                        doc.getSourceDocumentId(),
                        doc.getUpdatedAt()
                ))
                .toList();
        return new DocumentListResponse(items);
    }

    @Transactional
    public void updateStatus(String documentId, DocumentStatusUpdateRequest request) {
        Document document = documentRepository.findByIdInActiveWorkspace(documentId)
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
        Document doc = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        editStateInitializer.initializeIfNeeded(doc);
        Optional<DocumentEditState> editState = editStateRepository.findById(documentId);

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
                doc.getProcessingStage(),
                doc.getDisplayName(),
                fileTypeOf(doc),
                doc.getDocumentRole(),
                isEditable(doc, editState.isPresent()),
                doc.getCurrentVersion(),
                doc.getSourceDocumentId(),
                doc.getUpdatedAt(),
                editState.map(DocumentEditState::getMarkdown).orElse(null)
        );
    }

    private String areaOf(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE ? "pages" : "sources";
    }

    private String itemKindOf(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE ? "page" : "source_file";
    }

    private boolean isEditable(Document document, boolean hasEditState) {
        return document.getDeletedAt() == null
                && document.getDocumentRole() == DocumentRole.EDITABLE
                && hasEditState
                && (isMarkdown(document) || document.getStatus() == DocumentStatus.completed);
    }

    private boolean isMarkdown(Document document) {
        String mimeType = document.getMimeType();
        String filename = document.getFilename().toLowerCase(java.util.Locale.ROOT);
        return "text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType)
                || filename.endsWith(".md")
                || filename.endsWith(".markdown");
    }

    private String fileTypeOf(Document document) {
        int extensionIndex = document.getFilename().lastIndexOf('.');
        if (extensionIndex >= 0 && extensionIndex < document.getFilename().length() - 1) {
            return document.getFilename().substring(extensionIndex + 1)
                    .toLowerCase(java.util.Locale.ROOT);
        }
        return document.getMimeType();
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
    public DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseVersion,
            String source
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        if (baseVersion == null || baseVersion < 1) {
            throw new InvalidMarkdownContentException("base_version은 1 이상이어야 합니다.");
        }

        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        if (document.getCurrentVersion() != baseVersion) {
            throw versionConflict();
        }

        // 콘텐츠 버전 이력은 AI 편집(source=agent) 적용 시에만 남긴다. 수동 저장은 스냅샷하지 않는다.
        boolean recordVersion = "agent".equalsIgnoreCase(source);
        return applyContent(workspaceId, userId, document, markdown, baseVersion, recordVersion);
    }

    /**
     * 편집본을 적용해 문서 버전을 올린다. recordVersion=true면 결과를 콘텐츠 버전 스냅샷으로 남긴다.
     * 호출부에서 문서 로드·소유자·EDITABLE·version 검증을 이미 마쳤다고 가정한다.
     */
    private DocumentContentSaveResponse applyContent(
            String workspaceId, String userId, Document document, String markdown,
            long baseVersion, boolean recordVersion) {
        String documentId = document.getId();
        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState editState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new InvalidMarkdownContentException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다."));
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
        if (content.hasSameContent(document.getCurrentContentHash())) {
            return new DocumentContentSaveResponse(
                    documentId,
                    document.getCurrentVersion(),
                    document.getCurrentContentHash(),
                    document.getUpdatedAt(),
                    false
            );
        }

        Instant updatedAt = Instant.now();
        int updated = documentRepository.updateContentIfVersionMatches(
                documentId,
                workspaceId,
                baseVersion,
                content.contentHash(),
                content.bytes().length,
                updatedAt
        );
        if (updated == 0) {
            throw conditionalUpdateFailure(workspaceId, documentId);
        }
        editState.update(content.markdown(), content.contentHash(), updatedAt);
        if (recordVersion) {
            recordContentVersion(documentId, baseVersion + 1, content.markdown(), content.contentHash(), userId, updatedAt);
        }
        return new DocumentContentSaveResponse(
                documentId,
                baseVersion + 1,
                content.contentHash(),
                updatedAt,
                true
        );
    }

    private void recordContentVersion(String documentId, long version, String markdown,
                                      String contentHash, String createdBy, Instant createdAt) {
        contentVersionRepository.insertIfAbsent(documentId, version, markdown, contentHash, createdBy, createdAt);
    }

    /** 콘텐츠 버전 이력 목록(메타데이터만, 최신 버전 순). */
    @Transactional(readOnly = true)
    public DocumentContentVersionListResponse listContentVersions(
            String workspaceId, String userId, String documentId) {
        Document document = loadEditableForVersion(workspaceId, userId, documentId);
        List<DocumentContentVersionListResponse.Item> items = contentVersionRepository.findSummaries(documentId).stream()
                .map(s -> new DocumentContentVersionListResponse.Item(
                        s.getVersion(), s.getContentHash(), s.getCreatedBy(), s.getCreatedAt()))
                .toList();
        return new DocumentContentVersionListResponse(documentId, document.getCurrentVersion(), items);
    }

    /** 특정 버전의 전체 Markdown. */
    @Transactional(readOnly = true)
    public DocumentContentVersionResponse getContentVersion(
            String workspaceId, String userId, String documentId, long version) {
        loadEditableForVersion(workspaceId, userId, documentId);
        DocumentContentVersion snapshot = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, version))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, version));
        return new DocumentContentVersionResponse(documentId, snapshot.getVersion(), snapshot.getMarkdown(),
                snapshot.getContentHash(), snapshot.getCreatedBy(), snapshot.getCreatedAt());
    }

    /**
     * 과거 버전을 새 버전으로 복원한다(비파괴적). 해당 버전의 Markdown을 현재 편집본으로 저장해 version을 1 증가시키고
     * 새 스냅샷을 남긴다. base_version 낙관적 잠금으로 동시 편집 충돌을 막는다.
     */
    @Transactional
    public DocumentContentSaveResponse restoreContentVersion(
            String workspaceId, String userId, String documentId, long version, Long baseVersion) {
        if (baseVersion == null || baseVersion < 1) {
            throw new InvalidMarkdownContentException("base_version은 1 이상이어야 합니다.");
        }
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyWorkspaceOwnership(workspaceId, userId);
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 복원할 수 있습니다.");
        }
        if (document.getCurrentVersion() != baseVersion) {
            throw versionConflict();
        }
        DocumentContentVersion target = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, version))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, version));
        // 복원은 AI 편집이 아니므로 새 버전 스냅샷을 남기지 않는다.
        return applyContent(workspaceId, userId, document, target.getMarkdown(), baseVersion, false);
    }

    private Document loadEditableForVersion(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 버전 이력을 제공합니다.");
        }
        return document;
    }

    /**
     * 편집 가능 Markdown 문서를 최신 편집본으로 재ingest한다. DB 편집본을 MinIO 원본으로 승격(덮어쓰기)한 뒤
     * 파이프라인 처리 큐에 재등록해, 업로드 당시가 아닌 편집한 내용으로 Wiki가 만들어지게 한다.
     */
    @Transactional
    public DocumentIngestResponse ingest(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 재처리할 수 있습니다.");
        }
        if (document.getStatus() == DocumentStatus.processing) {
            throw new DocumentAlreadyProcessingException("이미 처리 중인 문서입니다.");
        }

        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState editState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new InvalidMarkdownContentException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다."));

        byte[] bytes = editState.getMarkdown().getBytes(StandardCharsets.UTF_8);
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
            throw new DocumentUploadException("문서 원본 갱신 중 오류가 발생했습니다.", e);
        }

        document.reopenForReingest(editState.getContentHash(), bytes.length);
        log.info("[문서 재ingest DB 갱신 완료] documentId={} contentHashPrefix={} byteSize={}",
                documentId, contentHashPrefix(editState.getContentHash()), bytes.length);
        requestProcessingAfterCommit(documentId);
        return new DocumentIngestResponse(documentId, document.getStatus());
    }

    @Transactional
    public DocumentRenameResponse rename(
            String workspaceId,
            String userId,
            String documentId,
            DocumentRenameRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        if (request == null || request.baseVersion() == null || request.baseVersion() < 1) {
            throw new InvalidDocumentFilenameException("base_version은 1 이상이어야 합니다.");
        }

        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getCurrentVersion() != request.baseVersion()) {
            throw versionConflict();
        }

        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(request.displayName(), document.getFilename());
        if (filename.filename().equals(document.getFilename())) {
            return new DocumentRenameResponse(
                    documentId,
                    document.getFilename(),
                    document.getDisplayName(),
                    document.getCurrentVersion(),
                    document.getUpdatedAt(),
                    false
            );
        }

        Instant updatedAt = Instant.now();
        int updated = documentRepository.renameIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                filename.filename(),
                filename.displayName(),
                filename.normalizedFilename(),
                updatedAt
        );
        if (updated == 0) {
            throw conditionalUpdateFailure(workspaceId, documentId);
        }
        return new DocumentRenameResponse(
                documentId,
                filename.filename(),
                filename.displayName(),
                request.baseVersion() + 1,
                updatedAt,
                true
        );
    }

    private void verifyDocumentOwner(Document document, String userId) {
        if (!document.getUserId().equals(userId)) {
            throw new DocumentWriteForbiddenException("문서 소유자만 변경할 수 있습니다.");
        }
    }

    private DocumentVersionConflictException versionConflict() {
        return new DocumentVersionConflictException(
                "다른 변경이 먼저 저장되었습니다. 최신 문서를 다시 조회해 주세요.");
    }

    private RuntimeException conditionalUpdateFailure(String workspaceId, String documentId) {
        if (documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .isEmpty()) {
            return new DocumentNotFoundException(documentId);
        }
        return versionConflict();
    }

    @Transactional
    public DocumentLifecycleResponse delete(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey,
            DocumentLifecycleRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        validateLifecycleRequest(request);
        Document document = documentRepository
                .findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        String endpointScope = "DELETE:/api/workspaces/" + workspaceId + "/documents";
        String requestHash = requestHash(
                documentId, "delete", Long.toString(request.baseVersion()));
        Optional<DocumentLifecycleResponse> replay = replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentLifecycleResponse.class, this::toLifecycleResponse);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (document.getDeletedAt() != null) {
            throw new DocumentNotFoundException(documentId);
        }
        Instant deletedAt = Instant.now();
        int updated = documentRepository.softDeleteIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                userId,
                deletedAt,
                UUID.randomUUID()
        );
        if (updated == 0) {
            throw conditionalUpdateFailure(workspaceId, documentId);
        }

        DocumentLifecycleResponse response = new DocumentLifecycleResponse(
                documentId,
                request.baseVersion() + 1,
                true,
                deletedAt,
                document.getSortOrder()
        );
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    public DocumentTrashResponse trash(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return new DocumentTrashResponse(
                documentRepository
                        .findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(workspaceId)
                        .stream()
                        .map(document -> new DocumentTrashResponse.DocumentTrashItem(
                                document.getId(),
                                document.getFilename(),
                                document.getDisplayName(),
                                document.getDocumentRole(),
                                document.getCurrentVersion(),
                                document.getDeletedAt(),
                                document.getDeletedBy(),
                                document.getDeleteOperationId(),
                                document.getSourceDocumentId()
                        ))
                        .toList()
        );
    }

    @Transactional
    public DocumentLifecycleResponse restore(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey,
            DocumentLifecycleRequest request
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        validateLifecycleRequest(request);
        Document document = documentRepository
                .findByIdAndWorkspaceIdForUpdate(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        String endpointScope = "POST:/api/workspaces/" + workspaceId + "/documents/restore";
        String requestHash = requestHash(
                documentId, "restore", Long.toString(request.baseVersion()));
        Optional<DocumentLifecycleResponse> replay = replayIdempotentRequest(
                userId, endpointScope, idempotencyKey, requestHash,
                DocumentLifecycleResponse.class, this::toLifecycleResponse);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (document.getDeletedAt() == null) {
            throw new DocumentNotFoundException(documentId);
        }
        // 원래 폴더가 아직 살아 있으면 그 폴더·순서로 복구하고, 사라졌으면 최상위 마지막에 배치한다.
        UUID originalFolderId = document.getSourceFolderId();
        boolean originalFolderActive = originalFolderId != null
                && sourceFolderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(originalFolderId, workspaceId)
                        .isPresent();
        UUID targetFolderId;
        long sortOrder;
        if (originalFolderActive) {
            targetFolderId = originalFolderId;
            sortOrder = document.getSortOrder();
        } else {
            targetFolderId = null;
            List<Document> rootItems = documentRepository.findRootItemsForUpdate(
                    workspaceId, document.getDocumentRole());
            sortOrder = rootItems.stream()
                    .mapToLong(Document::getSortOrder)
                    .max()
                    .orElse(-1) + 1;
        }
        Instant restoredAt = Instant.now();
        int updated = documentRepository.restoreIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
                targetFolderId,
                sortOrder,
                restoredAt
        );
        if (updated == 0) {
            if (documentRepository
                    .findByIdAndWorkspaceIdAndDeletedAtIsNotNull(documentId, workspaceId)
                    .isPresent()) {
                throw versionConflict();
            }
            throw new DocumentNotFoundException(documentId);
        }

        DocumentLifecycleResponse response = new DocumentLifecycleResponse(
                documentId,
                request.baseVersion() + 1,
                false,
                null,
                sortOrder
        );
        saveIdempotencyRecord(
                userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
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

    private void validateLifecycleRequest(DocumentLifecycleRequest request) {
        if (request == null || request.baseVersion() == null || request.baseVersion() < 1) {
            throw new InvalidDocumentVersionException(
                    "base_version은 1 이상의 정수여야 합니다.");
        }
    }

    private DocumentLifecycleResponse toLifecycleResponse(Document document) {
        return new DocumentLifecycleResponse(
                document.getId(),
                document.getCurrentVersion(),
                document.getDeletedAt() != null,
                document.getDeletedAt(),
                document.getSortOrder()
        );
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
        documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        List<DocumentBlockResponse> blocks = sourceBlockRepository
                .findAllByIdDocumentIdOrderByIdBlockIdAsc(documentId).stream()
                .map(b -> new DocumentBlockResponse(b.getBlockId(), b.getText()))
                .toList();

        return new DocumentBlocksResponse(documentId, blocks);
    }

    public DocumentOriginalResult getOriginal(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
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
