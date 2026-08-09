package fruition.core.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.util.StorageProperties;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentAsset;
import fruition.core.document.repository.DocumentAssetRepository;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.shared.idempotency.IdempotencyRecord;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentOriginalNotFoundException;
import fruition.core.document.exception.DocumentUploadException;
import fruition.core.document.exception.DocumentAlreadyProcessingException;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.exception.DocumentWriteForbiddenException;
import fruition.core.document.exception.InvalidDocumentFilenameException;
import fruition.core.document.exception.InvalidDocumentVersionException;
import fruition.shared.idempotency.InvalidIdempotencyKeyException;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.core.document.exception.InvalidMarkdownContentException;
import fruition.core.document.exception.MarkdownContentTooLargeException;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.DocumentContentVersionListResponse;
import fruition.core.document.dto.DocumentContentVersionResponse;
import fruition.core.document.dto.DocumentIngestResponse;
import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import fruition.core.document.exception.DocumentContentVersionNotFoundException;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.dto.DocumentDuplicateResponse;
import fruition.core.document.dto.DocumentListResponse;
import fruition.core.document.dto.DocumentLifecycleRequest;
import fruition.core.document.dto.DocumentLifecycleResponse;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.dto.DocumentOriginalResult;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.DocumentStatusUpdateRequest;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.dto.DocumentTrashResponse;
import fruition.core.document.dto.DocumentBlockResponse;
import fruition.core.document.dto.DocumentBlocksResponse;
import fruition.core.document.dto.DocumentWikiPageRef;
import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.domain.DocumentProcessingQueue;
import fruition.core.document.exception.DocumentConvertException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import fruition.core.document.repository.ConverterClient;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import fruition.core.document.repository.DocumentProcessingQueueRepository;
import fruition.core.document.repository.IngestCommandPublisher;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.mongo.MongoDocumentEditSaveResult;
import fruition.core.document.mongo.MongoDocumentEditState;
import fruition.core.document.mongo.MongoDocumentEditStore;
import fruition.shared.idempotency.IdempotencyRecordRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.exception.HierarchyItemNotFoundException;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.aihistory.service.IngestOperationStarter;
import fruition.core.aihistory.service.OperationRecorder;
import fruition.core.document.repository.SourceBlockRepository;
import fruition.core.wiki.domain.DocumentWikiLink;
import fruition.core.wiki.domain.DocumentWikiRelationType;
import fruition.core.wiki.domain.WikiPage;
import fruition.core.wiki.repository.DocumentWikiLinkRepository;
import fruition.core.wiki.repository.WikiPageLinkRepository;
import fruition.core.wiki.repository.WikiPageRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
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
    private static final String CONVERT_PLACEHOLDER_MARKDOWN = "PDF 변환 중...\n";

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final MinioClient minioClient;
    private final StorageProperties storageProps;
    private final IngestCommandPublisher ingestCommandPublisher;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final SourceBlockRepository sourceBlockRepository;
    private final DocumentProcessingQueueRepository queueRepository;
    private final DocumentConvertQueueRepository convertQueueRepository;
    private final ConverterClient converterClient;
    private final TransactionTemplate transactionTemplate;
    private final DocumentEditStateInitializer editStateInitializer;
    private final DocumentEditStateRepository editStateRepository;
    private final MongoDocumentEditStore mongoDocumentEditStore;
    private final DocumentContentVersionRepository contentVersionRepository;
    private final MarkdownDiffService markdownDiffService;
    private final DocumentEditLockService editLockService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final DocumentAssetReferenceSynchronizer assetReferenceSynchronizer;
    private final DocumentAssetReferenceParser assetReferenceParser;
    private final DocumentAssetRepository assetRepository;
    private final ObjectMapper objectMapper;
    private final AgentApplyOperationStore applyOperationStore;
    private final OperationRecorder operationRecorder;
    private final IngestOperationStarter ingestOperationStarter;
    private final String callbackBaseUrl;

    public DocumentService(DocumentRepository documentRepository,
                           FolderRepository folderRepository,
                           WorkspaceAccessGuard workspaceAccessGuard,
                           MinioClient minioClient,
                           StorageProperties storageProps,
                           IngestCommandPublisher ingestCommandPublisher,
                           DocumentWikiLinkRepository documentWikiLinkRepository,
                           WikiPageRepository wikiPageRepository,
                           WikiPageLinkRepository wikiPageLinkRepository,
                           SourceBlockRepository sourceBlockRepository,
                           DocumentProcessingQueueRepository queueRepository,
                           DocumentConvertQueueRepository convertQueueRepository,
                           ConverterClient converterClient,
                           TransactionTemplate transactionTemplate,
                           DocumentEditStateInitializer editStateInitializer,
                           DocumentEditStateRepository editStateRepository,
                           MongoDocumentEditStore mongoDocumentEditStore,
                           DocumentContentVersionRepository contentVersionRepository,
                           MarkdownDiffService markdownDiffService,
                           DocumentEditLockService editLockService,
                           IdempotencyRecordRepository idempotencyRecordRepository,
                           DocumentAssetReferenceSynchronizer assetReferenceSynchronizer,
                           DocumentAssetReferenceParser assetReferenceParser,
                           DocumentAssetRepository assetRepository,
                           ObjectMapper objectMapper,
                           AgentApplyOperationStore applyOperationStore,
                           OperationRecorder operationRecorder,
                           IngestOperationStarter ingestOperationStarter,
                           @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.minioClient = minioClient;
        this.storageProps = storageProps;
        this.ingestCommandPublisher = ingestCommandPublisher;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.wikiPageLinkRepository = wikiPageLinkRepository;
        this.sourceBlockRepository = sourceBlockRepository;
        this.queueRepository = queueRepository;
        this.convertQueueRepository = convertQueueRepository;
        this.converterClient = converterClient;
        this.transactionTemplate = transactionTemplate;
        this.editStateInitializer = editStateInitializer;
        this.editStateRepository = editStateRepository;
        this.mongoDocumentEditStore = mongoDocumentEditStore;
        this.contentVersionRepository = contentVersionRepository;
        this.markdownDiffService = markdownDiffService;
        this.editLockService = editLockService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.assetReferenceSynchronizer = assetReferenceSynchronizer;
        this.assetReferenceParser = assetReferenceParser;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
        this.applyOperationStore = applyOperationStore;
        this.operationRecorder = operationRecorder;
        this.ingestOperationStarter = ingestOperationStarter;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    @Transactional
    public DocumentUploadResponse upload(
            String workspaceId,
            String userId,
            String idempotencyKey,
            UUID folderId,
            MultipartFile file
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        verifyFolder(workspaceId, folderId);
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
            document.place(folderId, placementSortOrder(workspaceId, folderId, document.getDocumentRole()));
            document.updateStatus(DocumentStatus.uploaded, null, null, null);
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
        verifyFolder(workspaceId, request.folderId());

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
                createMarkdownDocument(workspaceId, userId, filename.filename(), content, "direct", request.folderId());
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
        Document source = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(source, userId);
        if (source.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new DocumentWriteForbiddenException("편집 가능한 Markdown 문서만 복제할 수 있습니다.");
        }

        List<Document> siblings =
                documentRepository.findSiblingPagesForUpdate(workspaceId, source.getFolderId());
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

        Set<String> existingNames = siblings.stream()
                .map(Document::getNormalizedFilename)
                .collect(Collectors.toSet());
        DocumentEditingRules.Filename duplicateFilename =
                DocumentEditingRules.duplicateFilename(source.getDisplayName(), existingNames);
        long sortOrder = siblings.stream()
                .mapToLong(Document::getSortOrder)
                .max()
                .orElse(-1) + 1;
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
                storeMarkdownSource(duplicateId, content.bytes()),
                null,
                "duplicate"
        );
        duplicate.initializeDuplicate(
                source.getId(),
                source.getFolderId(),
                content.contentHash(),
                content.bytes().length,
                sortOrder
        );
        documentRepository.save(duplicate);
        editStateRepository.save(new DocumentEditState(
                duplicateId, content.markdown(), content.contentHash()));
        assetReferenceSynchronizer.copyReferences(documentId, duplicateId);

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
                    "direct",
                    null
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
            String origin,
            UUID folderId
    ) {
        String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        Document document = new Document(
                documentId,
                workspaceId,
                userId,
                filename,
                "text/markdown",
                content.bytes().length,
                storeMarkdownSource(documentId, content.bytes()),
                null,
                origin
        );
        long sortOrder = placementSortOrder(workspaceId, folderId, DocumentRole.EDITABLE);
        document.initializeDirectMarkdown(
                content.contentHash(),
                content.bytes().length,
                sortOrder
        );
        document.place(folderId, sortOrder);
        documentRepository.save(document);
        editStateRepository.save(new DocumentEditState(
                documentId, content.markdown(), content.contentHash()));
        return toUploadResponse(document, true);
    }

    private long nextRootSortOrder(String workspaceId, DocumentRole documentRole) {
        return documentRepository.findMaxRootSortOrder(workspaceId, documentRole) + 1;
    }

    /** 폴더 지정 시 폴더·문서 혼합 순서 마지막, 미지정 시 역할별 최상위 마지막에 배치한다. */
    private long placementSortOrder(String workspaceId, UUID folderId, DocumentRole documentRole) {
        if (folderId == null) {
            return nextRootSortOrder(workspaceId, documentRole);
        }
        return Math.max(
                folderRepository.findMaxSortOrder(workspaceId, folderId),
                documentRepository.findMaxSortOrderInFolder(workspaceId, folderId)) + 1;
    }

    private void verifyFolder(String workspaceId, UUID folderId) {
        if (folderId != null
                && folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId).isEmpty()) {
            throw new HierarchyItemNotFoundException("대상 폴더를 찾을 수 없습니다.");
        }
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
                document.getFolderId(),
                document.getSourceDocumentId(),
                document.getSortOrder()
        );
    }

    private String requestHash(String filename, String mimeType, String contentHash) {
        return sha256((filename + "\0" + mimeType + "\0" + contentHash)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 새로 만드는 Markdown 문서의 원본을 object storage에 저장하고 object key를 돌려준다.
     * 파이프라인은 Mongo를 읽지 못하고 source_uri로만 본문을 가져가므로,
     * 문서 행을 만들 때 원본도 같이 만들어야 이후 ingest가 성립한다.
     */
    private String storeMarkdownSource(String documentId, byte[] bytes) {
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
        } catch (Exception e) {
            throw new DocumentUploadException("문서 원본 저장 중 오류가 발생했습니다.", e);
        }
        registerMinioRollbackCleanup(objectPath);
        return objectPath;
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

    /**
     * 작업 큐 행을 바깥 트랜잭션이 커밋된 뒤에 등록한다.
     * 커밋 전에 넣으면 워커가 아직 보이지 않는 문서를 집어갈 수 있어 순서를 보장해야 한다.
     *
     * @param queueLabel 로그에 쓰는 큐 이름 (예: "문서 처리 큐")
     * @param enqueue    큐 행 저장. 새 트랜잭션 안에서 실행된다.
     */
    private void enqueueAfterCommit(String queueLabel, String documentId, Runnable enqueue) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.info("[{} 즉시 등록] documentId={} transactionActive=false", queueLabel, documentId);
            transactionTemplate.execute(status -> {
                enqueue.run();
                log.info("[{} 등록 완료] documentId={} status=pending", queueLabel, documentId);
                return null;
            });
            return;
        }
        log.info("[{} 등록 예약] documentId={} afterCommit=true", queueLabel, documentId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // afterCommit 시점에는 바깥 트랜잭션 리소스가 아직 스레드에 묶여 있어
                // 기본 REQUIRED로 참여하면 INSERT가 커밋되지 않고 버려진다 — 반드시 새 트랜잭션.
                TransactionTemplate requiresNew =
                        new TransactionTemplate(transactionTemplate.getTransactionManager());
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.execute(status -> {
                    enqueue.run();
                    log.info("[{} 등록 완료] documentId={} status=pending", queueLabel, documentId);
                    return null;
                });
            }
        });
    }

    private void requestProcessingAfterCommit(String documentId) {
        enqueueAfterCommit("문서 처리 큐", documentId,
                () -> queueRepository.save(new DocumentProcessingQueue(documentId)));
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
        registerMinioRollbackCleanup(objectPath);

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
                            .object(normalizeObjectKey(document.getSourceUri()))
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

    /**
     * PDF 원본 문서의 Markdown 변환을 요청한다. 변환 결과를 담을 placeholder Markdown 문서를 즉시 만들어
     * 반환하고, 실제 변환은 convert queue worker가 백그라운드에서 수행한다.
     */
    @Transactional
    public DocumentUploadResponse convertToMarkdown(
            String workspaceId,
            String userId,
            String documentId,
            String idempotencyKey
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateIdempotencyKey(idempotencyKey);
        Document source = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!isPdf(source)) {
            throw new InvalidDocumentConvertRequestException("PDF 원본 문서만 Markdown으로 변환할 수 있습니다.");
        }
        if (source.getSourceUri() == null) {
            throw new InvalidDocumentConvertRequestException("원본 파일이 없는 문서는 변환할 수 없습니다.");
        }

        String endpointScope = convertEndpointScope(workspaceId);
        String requestHash = requestHash(documentId, "convert-markdown", "");
        Optional<DocumentUploadResponse> replay =
                replayIdempotentRequest(userId, endpointScope, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // placeholder: display_name은 원본에서 확장자를 뗀 이름, filename은 <이름>.md
        DocumentEditingRules.Filename filename =
                DocumentEditingRules.rename(source.getDisplayName(), "document.md");
        DocumentEditingRules.MarkdownContent content =
                DocumentEditingRules.markdown(CONVERT_PLACEHOLDER_MARKDOWN);
        String placeholderId = "doc_" + UUID.randomUUID().toString().replace("-", "");
        Document placeholder = new Document(
                placeholderId,
                workspaceId,
                userId,
                filename.filename(),
                "text/markdown",
                content.bytes().length,
                storeMarkdownSource(placeholderId, content.bytes()),
                null,
                "convert"
        );
        long sortOrder = placementSortOrder(workspaceId, source.getFolderId(), DocumentRole.EDITABLE);
        placeholder.initializeConvertPlaceholder(
                source.getId(),
                source.getFolderId(),
                content.contentHash(),
                content.bytes().length,
                sortOrder
        );
        documentRepository.save(placeholder);
        editStateRepository.save(new DocumentEditState(
                placeholderId, content.markdown(), content.contentHash()));
        requestConvertAfterCommit(placeholderId, source.getId());

        DocumentUploadResponse response = toUploadResponse(placeholder, true);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        log.info("[문서 변환 요청 등록] workspaceId={} sourceDocumentId={} placeholderDocumentId={}",
                workspaceId, documentId, placeholderId);
        return response;
    }

    private boolean isPdf(Document document) {
        return "application/pdf".equals(document.getMimeType())
                || document.getNormalizedFilename().endsWith(".pdf");
    }

    private String convertEndpointScope(String workspaceId) {
        return "POST:/api/workspaces/" + workspaceId + "/documents/convert-markdown";
    }

    private void requestConvertAfterCommit(String documentId, String sourceDocumentId) {
        enqueueAfterCommit("문서 변환 큐", documentId,
                () -> convertQueueRepository.save(new DocumentConvertQueue(documentId, sourceDocumentId)));
    }

    /**
     * convert queue worker 전용. 원본 PDF를 변환기로 변환해 placeholder Markdown 문서 본문에 반영한다.
     * 실패(변환기 4xx/5xx·timeout·원본 읽기 실패)는 placeholder 문서를 failed로 반영하고 원인을 로그로 남긴다.
     */
    void doConvert(long queueId, String documentId, String sourceDocumentId) {
        Document placeholder = documentRepository.findByIdInActiveWorkspace(documentId).orElse(null);
        if (placeholder == null) {
            log.warn("[문서 변환 생략] documentId={} reason=document_not_found", documentId);
            return;
        }
        try {
            Document source = documentRepository.findById(sourceDocumentId)
                    .orElseThrow(() -> new DocumentConvertException(
                            "원본 문서를 찾을 수 없습니다: " + sourceDocumentId));
            byte[] pdfBytes = readOriginalBytes(source);
            log.info("[문서 변환 시작] documentId={} sourceDocumentId={} pdfByteSize={}",
                    documentId, sourceDocumentId, pdfBytes.length);
            String markdown = converterClient.convertPdf(source.getFilename(), pdfBytes);
            DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
            applyConvertedMarkdown(queueId, placeholder, content);
            log.info("[문서 변환 완료] documentId={} sourceDocumentId={} markdownByteSize={}",
                    documentId, sourceDocumentId, content.bytes().length);
        } catch (Exception e) {
            // DocumentConvertException 메시지에 변환기 상태 코드(422/504/503 등) 원인이 담겨 온다.
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findByIdInActiveWorkspace(documentId).ifPresent(doc ->
                        doc.markProcessingFailed("PDF 변환에 실패했습니다: " + e.getMessage(), now));
                return null;
            });
            log.warn("[문서 변환 실패 반영] documentId={} sourceDocumentId={} error={}",
                    documentId, sourceDocumentId, e.getMessage());
        }
    }

    private byte[] readOriginalBytes(Document source) {
        if (source.getSourceUri() == null) {
            throw new DocumentConvertException("원본 파일 경로가 없습니다: " + source.getId());
        }
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(storageProps.getBucket())
                        .object(normalizeObjectKey(source.getSourceUri()))
                        .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new DocumentConvertException("원본 PDF를 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 변환 Markdown을 placeholder 문서에 반영한다. 시스템 쓰기라 revision 충돌 우려가 없어 base_revision 1로
     * 저장하고, write_id({@code convert:<queueId>}) 재시도는 Mongo write receipt가 멱등하게 처리한다.
     */
    private void applyConvertedMarkdown(
            long queueId,
            Document placeholder,
            DocumentEditingRules.MarkdownContent content
    ) {
        DocumentEditState legacyState = editStateRepository.findById(placeholder.getId())
                .orElseThrow(() -> new DocumentConvertException(
                        "placeholder 편집 상태를 찾을 수 없습니다: " + placeholder.getId()));
        MongoDocumentEditSaveResult result = mongoDocumentEditStore.save(
                placeholder.getWorkspaceId(),
                placeholder.getId(),
                content.markdown(),
                content.contentHash(),
                1L,
                "convert:" + queueId,
                placeholder.getUserId(),
                placeholder.getCurrentVersion(),
                legacyState
        );
        projectContentVersions(placeholder.getId(), content.markdown(), result);
        Instant now = Instant.now();
        transactionTemplate.execute(status -> {
            documentRepository.findByIdInActiveWorkspace(placeholder.getId()).ifPresent(doc ->
                    doc.completeConvert(content.contentHash(), content.bytes().length, now));
            return null;
        });
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
        // llmPipeline 호출 전에 AI 작업 로그를 processing으로 먼저 커밋한다.
        // 콜백이 도착했을 때 대조할 등록값이 없으면 결과를 받아들일 수 없다.
        String operationId = ingestOperationStarter
                .start(document.getWorkspaceId(), document.getUserId(), documentId)
                .orElse(null);
        try {
            String runId = ingestCommandPublisher.publish(documentId, document.getUserId(), document.getWorkspaceId(),
                    callbackUrl, document.getSelectionMode(), document.getPipelineInputMarkdown(), chatWiki,
                    operationId,
                    operationId == null ? null : ingestOperationStarter.resultCallbackUrl(operationId));
            Instant now = Instant.now();
            transactionTemplate.execute(status -> {
                documentRepository.findByIdInActiveWorkspace(documentId)
                        .ifPresent(doc -> doc.markPipelineStarted(runId, now));
                return null;
            });
            log.info("[문서 처리 run 기록 완료] documentId={} runId={}", documentId, runId);
        } catch (Exception e) {
            Instant now = Instant.now();
            if (operationId != null) {
                ingestOperationStarter.markFailed(operationId,
                        "ingest command 발행에 실패했습니다: " + e.getMessage());
            }
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
                        doc.getUpdatedAt(),
                        needsReingest(doc)
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
        Optional<MongoDocumentEditState> mongoEditState = mongoDocumentEditStore.findState(documentId);

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
                isEditable(doc, mongoEditState.isPresent() || editState.isPresent()),
                doc.getCurrentVersion(),
                mongoEditState.map(MongoDocumentEditState::getRevision).orElse(doc.getCurrentVersion()),
                doc.getSourceDocumentId(),
                mongoEditState.map(MongoDocumentEditState::getUpdatedAt)
                        .orElse(doc.getUpdatedAt()),
                mongoEditState.map(MongoDocumentEditState::getMarkdown)
                        .orElseGet(() -> editState.map(DocumentEditState::getMarkdown).orElse(null)),
                editLockService.getStatus(doc.getId())
        );
    }

    private String areaOf(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE ? "pages" : "sources";
    }

    private String itemKindOf(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE ? "page" : "source_file";
    }

    /**
     * 마지막 ingest 스냅샷(content_hash)과 현재 편집본(current_content_hash)이 다르면 재분석이 필요하다.
     * 처리 중이면 이미 재분석이 진행 중이므로 제외한다. 실패(failed)는 기존 오류 표시가 담당한다.
     */
    private boolean needsReingest(Document document) {
        return document.getDocumentRole() == DocumentRole.EDITABLE
                && document.getStatus() != DocumentStatus.processing
                && document.getCurrentContentHash() != null
                && document.getContentHash() != null
                && !document.getCurrentContentHash().equals(document.getContentHash());
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

    public DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source
    ) {
        return saveContent(workspaceId, userId, documentId, markdown, baseRevision, revisionWriteId, source, null);
    }

    /**
     * 본문 저장은 MongoDB store가 한 transaction으로 처리한다(state·write receipt·outbox).
     * PostgreSQL에는 version read model(document_content_versions)만 projection한다.
     *
     * @param applyOperationId Agent turn에서 발급한 적용 표. 검증에 성공하면 AI 작업 로그를 남긴다.
     *                         {@code source} 문자열은 클라이언트가 임의로 넣을 수 있어 신뢰하지 않는다.
     */
    public DocumentContentSaveResponse saveContent(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            Long baseRevision,
            String revisionWriteId,
            String source,
            String applyOperationId
    ) {
        verifyWorkspaceOwnership(workspaceId, userId);
        if (baseRevision == null || baseRevision < 1) {
            throw new InvalidMarkdownContentException("base_revision은 1 이상이어야 합니다.");
        }
        validateRevisionWriteId(revisionWriteId);

        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        editLockService.requireWritable(documentId, userId);

        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState legacyState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new InvalidMarkdownContentException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다."));
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
        MongoDocumentEditSaveResult result;
        try {
            result = mongoDocumentEditStore.save(
                    workspaceId,
                    documentId,
                    content.markdown(),
                    content.contentHash(),
                    baseRevision,
                    revisionWriteId,
                    userId,
                    document.getCurrentVersion(),
                    legacyState
            );
        } catch (DocumentVersionConflictException conflict) {
            // 편집안이 오래된 base를 바탕으로 하고 있다. 시도 기록은 별도 트랜잭션으로 남긴다.
            if (applyOperationStore.consume(applyOperationId, userId, documentId)) {
                operationRecorder.recordConflict(
                        applyOperationId, workspaceId, userId, documentId, Instant.now());
            }
            throw conflict;
        }
        projectContentVersions(documentId, content.markdown(), result);
        if (result.changed()) {
            // 재ingest 필요 판단용 projection: 목록 API가 PG만으로 현재 편집본 해시를 비교할 수 있게 한다.
            documentRepository.updateCurrentContentHash(documentId, result.contentHash(), result.updatedAt());
            // 이미지를 첨부하지 않는 저장에서도 본문에 남은 관리 이미지를 기준으로 참조를 맞춘다.
            // 그러지 않으면 본문에서 지운 이미지가 참조된 상태로 남아 정리 대상이 되지 않는다.
            assetReferenceSynchronizer.synchronize(
                    documentId, workspaceId, assetReferenceParser.parse(content.markdown()));
        }

        // Backend가 발급한 적용 표가 확인될 때만 AI 작업으로 기록한다.
        if (result.changed() && applyOperationStore.consume(applyOperationId, userId, documentId)) {
            transactionTemplate.execute(status -> {
                operationRecorder.recordDocumentEdit(applyOperationId, workspaceId, userId, documentId,
                        result.baseRevision(), result.revision(), result.baseMarkdown(),
                        content.markdown(), result.updatedAt());
                contentVersionRepository.linkOperation(documentId, result.revision(), applyOperationId);
                return null;
            });
        }

        return new DocumentContentSaveResponse(
                documentId,
                result.revision(),
                result.contentHash(),
                result.updatedAt(),
                result.changed()
        );
    }

    private void validateRevisionWriteId(String revisionWriteId) {
        if (revisionWriteId == null || revisionWriteId.isBlank() || revisionWriteId.length() > 255) {
            throw new InvalidIdempotencyKeyException(
                    "revision_write_id는 1자 이상 255자 이하여야 합니다.");
        }
    }

    /**
     * Mongo 저장 결과를 PostgreSQL version read model로 projection한다.
     * commit 뒤 projection이 실패해도 같은 revision_write_id 재시도가 receipt를 replay해 복구한다.
     */
    private void projectContentVersions(
            String documentId,
            String resultMarkdown,
            MongoDocumentEditSaveResult result
    ) {
        if (!result.changed()) {
            return;
        }
        recordContentVersion(
                documentId,
                result.baseRevision(),
                result.baseMarkdown(),
                result.baseContentHash(),
                result.actorUserId(),
                result.updatedAt()
        );
        recordContentVersion(
                documentId,
                result.revision(),
                resultMarkdown,
                result.contentHash(),
                result.actorUserId(),
                result.updatedAt()
        );
    }

    /**
     * object storage 업로드 전에 인가·역할·편집 잠금을 먼저 확인한다.
     *
     * <p>base revision은 여기서 보지 않는다. 재전송된 같은 요청은 base가 이미 지나가 있어
     * 여기서 막으면 저장 계층의 revision_write_id 중복 판정에 닿지 못하고 첫 저장이
     * 실패로 보인다. revision 판정은 canonical인 Mongo 저장 시점이 담당한다.
     */
    @Transactional(readOnly = true)
    public void validateContentSavePreconditions(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new InvalidMarkdownContentException("편집 가능한 Markdown 문서만 저장할 수 있습니다.");
        }
        editLockService.requireWritable(documentId, userId);
    }

    /**
     * 이미지 포함 저장. asset row는 본문보다 먼저 커밋해 참조 무결성을 확보하고
     * (실패 시 orphan은 cleanup worker가 정리), 본문 저장은 Mongo 기반 saveContent를 재사용한다.
     *
     * <p>{@code revisionWriteId}와 asset ID가 모두 요청 내용에서 결정되므로, 같은 요청이
     * 재전송되면 본문까지 동일해져 Mongo가 첫 저장 결과를 그대로 돌려준다.
     */
    public DocumentContentSaveResponse saveContentWithAssets(
            String workspaceId,
            String userId,
            String documentId,
            String markdown,
            long baseVersion,
            String revisionWriteId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> storedAssets,
            String applyOperationId
    ) {
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown(markdown);
        Instant updatedAt = Instant.now();
        List<DocumentAsset> assets = storedAssets.values().stream()
                .map(stored -> new DocumentAsset(
                        stored.assetId(), workspaceId, userId,
                        stored.validated().originalFilename(), stored.validated().contentType(),
                        stored.validated().bytes().length, stored.validated().width(), stored.validated().height(),
                        stored.validated().contentHash(), stored.objectKey(), updatedAt))
                .toList();
        transactionTemplate.execute(status -> {
            assetRepository.saveAll(assets);
            return null;
        });

        DocumentContentSaveResponse saved;
        try {
            saved = saveContent(workspaceId, userId, documentId, content.markdown(),
                    baseVersion, revisionWriteId, null, applyOperationId);
        } catch (RuntimeException exception) {
            transactionTemplate.execute(status -> {
                assetRepository.deleteAllInBatch(assets);
                return null;
            });
            throw exception;
        }
        if (!saved.changed()) {
            // 본문이 그대로면 새 asset row도 남기지 않는다. object storage 정리는 호출부가 한다.
            transactionTemplate.execute(status -> {
                assetRepository.deleteAllInBatch(assets);
                return null;
            });
        }
        return new DocumentContentSaveResponse(
                saved.documentId(), saved.currentVersion(), saved.contentHash(), saved.updatedAt(),
                saved.changed(), content.markdown(), List.of());
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
        long editRevision = mongoDocumentEditStore.findState(documentId)
                .map(MongoDocumentEditState::getRevision)
                .orElse(document.getCurrentVersion());
        return new DocumentContentVersionListResponse(documentId, editRevision, items);
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

    /** 두 콘텐츠 버전의 줄 단위 변경 사항. */
    @Transactional(readOnly = true)
    public DocumentContentDiffResponse compareContentVersions(
            String workspaceId, String userId, String documentId, long fromVersion, long toVersion) {
        loadEditableForVersion(workspaceId, userId, documentId);
        DocumentContentVersion before = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, fromVersion))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, fromVersion));
        DocumentContentVersion after = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, toVersion))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, toVersion));
        return markdownDiffService.compare(
                documentId, fromVersion, before.getMarkdown(), toVersion, after.getMarkdown());
    }

    /**
     * 과거 버전을 새 버전으로 복원한다(비파괴적). 해당 버전의 Markdown을 현재 편집본으로 저장해 version을 1 증가시킨다.
     * base_version 낙관적 잠금으로 동시 편집 충돌을 막는다.
     */
    @Transactional
    public DocumentContentSaveResponse restoreContentVersion(
            String workspaceId, String userId, String documentId, long version, Long baseVersion) {
        loadEditableForVersion(workspaceId, userId, documentId);
        DocumentContentVersion target = contentVersionRepository
                .findById(new DocumentContentVersionId(documentId, version))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(documentId, version));
        return saveContent(
                workspaceId,
                userId,
                documentId,
                target.getMarkdown(),
                baseVersion,
                "restore:" + version + ":" + baseVersion,
                null
        );
    }

    private Document loadEditableForVersion(String workspaceId, String userId, String documentId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        verifyDocumentOwner(document, userId);
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
        editLockService.requireWritable(documentId, userId);
        if (document.getStatus() == DocumentStatus.processing) {
            throw new DocumentAlreadyProcessingException("이미 처리 중인 문서입니다.");
        }

        editStateInitializer.initializeIfNeeded(document);
        DocumentEditState editState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new InvalidMarkdownContentException(
                        "현재 Markdown 편집 상태를 찾을 수 없습니다."));
        // 최신 편집본은 Mongo가 canonical이다. 없으면 legacy PG 상태로 대체한다.
        Optional<MongoDocumentEditState> mongoEditState = mongoDocumentEditStore.findState(documentId);
        String currentMarkdown = mongoEditState.map(MongoDocumentEditState::getMarkdown)
                .orElse(editState.getMarkdown());
        String currentContentHash = mongoEditState.map(MongoDocumentEditState::getContentHash)
                .orElse(editState.getContentHash());

        // 원본 경로가 없으면 빈 키로 putObject가 나가 원인을 알기 어려운 500이 된다.
        // 문서 생성 시점에 원본을 만들지 않던 시절의 행이 여기로 들어온다.
        if (document.getSourceUri() == null || document.getSourceUri().isBlank()) {
            throw new InvalidMarkdownContentException(
                    "원본 파일 경로가 없는 문서는 재처리할 수 없습니다: " + documentId);
        }

        byte[] bytes = currentMarkdown.getBytes(StandardCharsets.UTF_8);
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(storageProps.getBucket())
                            .object(normalizeObjectKey(document.getSourceUri()))
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/markdown")
                            .build()
            );
        } catch (Exception e) {
            throw new DocumentUploadException("문서 원본 갱신 중 오류가 발생했습니다.", e);
        }

        document.reopenForReingest(currentContentHash, bytes.length);
        log.info("[문서 재ingest DB 갱신 완료] documentId={} contentHashPrefix={} byteSize={}",
                documentId, contentHashPrefix(currentContentHash), bytes.length);
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
        List<Document> rootItems = documentRepository.findRootItemsForUpdate(
                workspaceId, document.getDocumentRole());
        long sortOrder = rootItems.stream()
                .mapToLong(Document::getSortOrder)
                .max()
                .orElse(-1) + 1;
        Instant restoredAt = Instant.now();
        int updated = documentRepository.restoreIfVersionMatches(
                documentId,
                workspaceId,
                request.baseVersion(),
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
