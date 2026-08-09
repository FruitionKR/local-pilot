package fruition.core.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentConvertQueue;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.exception.DocumentConvertException;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.InvalidDocumentConvertRequestException;
import fruition.core.document.mongo.MongoDocumentEditSaveResult;
import fruition.core.document.mongo.MongoDocumentEditStore;
import fruition.core.document.repository.ConverterClient;
import fruition.core.document.repository.DocumentConvertQueueRepository;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.FolderRepository;
import fruition.core.document.repository.IngestCommandOutbox;
import fruition.shared.idempotency.IdempotencyConflictException;
import fruition.shared.idempotency.IdempotencyRecord;
import fruition.shared.idempotency.IdempotencyRecordRepository;
import fruition.shared.util.StorageProperties;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceConvertTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String SOURCE_DOCUMENT_ID = "doc_source_pdf";

    @Mock DocumentRepository documentRepository;
    @Mock FolderRepository folderRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock MinioClient minioClient;
    @Mock StorageProperties storageProps;
    @Mock IngestCommandOutbox ingestCommandOutbox;
    @Mock PipelineWikiStateRequester pipelineWikiStateRequester;
    @Mock DocumentConvertQueueRepository convertQueueRepository;
    @Mock ConverterClient converterClient;
    @Mock TransactionTemplate transactionTemplate;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock MongoDocumentEditStore mongoDocumentEditStore;
    @Mock DocumentContentVersionRepository contentVersionRepository;
    @Mock MarkdownDiffService markdownDiffService;
    @Mock DocumentEditLockService editLockService;
    @Mock IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock DocumentAssetReferenceSynchronizer assetReferenceSynchronizer;
    @Mock DocumentAssetReferenceParser assetReferenceParser;
    @Mock fruition.core.document.repository.DocumentAssetRepository assetRepository;
    @Mock fruition.core.aihistory.service.OperationRecorder operationRecorder;
    @Mock fruition.core.aihistory.service.IngestOperationStarter ingestOperationStarter;
    @Mock fruition.core.aihistory.service.AgentApplyOperationStore applyOperationStore;

    DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, folderRepository,
                workspaceAccessGuard, minioClient, storageProps,
                ingestCommandOutbox, pipelineWikiStateRequester,
                convertQueueRepository, converterClient, transactionTemplate,
                editStateInitializer, editStateRepository, mongoDocumentEditStore,
                contentVersionRepository, markdownDiffService,
                editLockService, idempotencyRecordRepository,
                assetReferenceSynchronizer,
                assetReferenceParser, assetRepository,
                new ObjectMapper().findAndRegisterModules(),
                applyOperationStore,
                operationRecorder,
                ingestOperationStarter);
        // 변환 placeholder도 생성 시점에 원본을 object storage에 쓴다.
        lenient().when(storageProps.getBucket()).thenReturn("fruition-storage");
        // 단위 테스트에서는 transactionTemplate이 콜백을 그대로 실행하게 한다.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
    }

    private Document sourcePdf() {
        return new Document(SOURCE_DOCUMENT_ID, WORKSPACE_ID, USER_ID, "보고서.pdf",
                "application/pdf", 1234L, "sources/documents/" + SOURCE_DOCUMENT_ID + "/original", "pdf-hash");
    }

    private void stubOwnedWorkspace() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("PDF 원본 변환 요청은 processing 상태의 placeholder Markdown 문서를 만들고 변환 큐에 등록한다")
    void convertToMarkdown_pdfSource_createsPlaceholderAndEnqueues() {
        stubOwnedWorkspace();
        Document source = sourcePdf();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                eq(USER_ID), anyString(), eq("convert-key"))).thenReturn(Optional.empty());
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(2L);

        DocumentUploadResponse response = documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key");

        ArgumentCaptor<Document> savedDocument = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(savedDocument.capture());
        Document placeholder = savedDocument.getValue();
        assertThat(placeholder.getFilename()).isEqualTo("보고서.md");
        assertThat(placeholder.getDisplayName()).isEqualTo("보고서");
        assertThat(placeholder.getMimeType()).isEqualTo("text/markdown");
        assertThat(placeholder.getDocumentRole()).isEqualTo(DocumentRole.EDITABLE);
        assertThat(placeholder.getStatus()).isEqualTo(DocumentStatus.processing);
        assertThat(placeholder.getSourceDocumentId()).isEqualTo(SOURCE_DOCUMENT_ID);
        assertThat(placeholder.getSortOrder()).isEqualTo(3L);
        assertThat(placeholder.getOrigin()).isEqualTo("convert");

        ArgumentCaptor<DocumentEditState> savedEditState = ArgumentCaptor.forClass(DocumentEditState.class);
        verify(editStateRepository).save(savedEditState.capture());
        assertThat(savedEditState.getValue().getDocumentId()).isEqualTo(placeholder.getId());
        assertThat(savedEditState.getValue().getMarkdown()).contains("PDF 변환 중");

        ArgumentCaptor<DocumentConvertQueue> savedQueue = ArgumentCaptor.forClass(DocumentConvertQueue.class);
        verify(convertQueueRepository).save(savedQueue.capture());
        assertThat(savedQueue.getValue().getDocumentId()).isEqualTo(placeholder.getId());
        assertThat(savedQueue.getValue().getSourceDocumentId()).isEqualTo(SOURCE_DOCUMENT_ID);
        assertThat(savedQueue.getValue().getStatus()).isEqualTo("pending");

        verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
        assertThat(response.id()).isEqualTo(placeholder.getId());
        assertThat(response.status()).isEqualTo(DocumentStatus.processing);
        assertThat(response.editable()).isTrue();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
    }

    @Test
    @DisplayName("PDF 원본이 아니면 InvalidDocumentConvertRequestException을 던진다")
    void convertToMarkdown_nonPdf_throws() {
        stubOwnedWorkspace();
        Document markdownDocument = new Document("doc_md", WORKSPACE_ID, USER_ID, "노트.md",
                "text/markdown", 10L, null, null);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_md", WORKSPACE_ID))
                .thenReturn(Optional.of(markdownDocument));

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, "doc_md", "convert-key"))
                .isInstanceOf(InvalidDocumentConvertRequestException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("문서가 없으면 DocumentNotFoundException을 던진다")
    void convertToMarkdown_unknownDocument_throws() {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_unknown", WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, "doc_unknown", "convert-key"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    @DisplayName("같은 Idempotency-Key 재요청은 저장된 응답을 재생하고 새 문서를 만들지 않는다")
    void convertToMarkdown_idempotentReplay_returnsStoredResponse() throws Exception {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(sourcePdf()));
        String requestHash = sha256(SOURCE_DOCUMENT_ID + "\0convert-markdown\0");
        Instant now = Instant.now();
        String responseBody = """
                {"id":"doc_replay","filename":"보고서.md","mime_type":"text/markdown","byte_size":10,
                 "status":"processing","source_uri":null,"uploaded_at":"%s","editable":true,
                 "current_version":1,"document_role":"EDITABLE"}
                """.formatted(now);
        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                USER_ID, "POST:/api/workspaces/" + WORKSPACE_ID + "/documents/convert-markdown", "convert-key"))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        UUID.randomUUID(), USER_ID,
                        "POST:/api/workspaces/" + WORKSPACE_ID + "/documents/convert-markdown",
                        "convert-key", requestHash, 201, "doc_replay", responseBody,
                        now, now.plusSeconds(3600))));

        DocumentUploadResponse response = documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key");

        assertThat(response.id()).isEqualTo("doc_replay");
        verify(documentRepository, never()).save(any());
        verify(convertQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 Idempotency-Key를 다른 요청에 쓰면 IdempotencyConflictException을 던진다")
    void convertToMarkdown_idempotencyKeyReuse_throwsConflict() {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(SOURCE_DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(sourcePdf()));
        Instant now = Instant.now();
        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                eq(USER_ID), anyString(), eq("convert-key")))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        UUID.randomUUID(), USER_ID, "scope", "convert-key",
                        "다른-요청-hash", 201, "doc_other", null, now, now.plusSeconds(3600))));

        assertThatThrownBy(() -> documentService.convertToMarkdown(
                WORKSPACE_ID, USER_ID, SOURCE_DOCUMENT_ID, "convert-key"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("변환 성공 시 Mongo edit store에 convert write_id로 저장하고 문서를 completed로 반영한다")
    void doConvert_success_appliesMarkdownAndCompletes() throws Exception {
        Document placeholder = placeholderDocument();
        Document source = sourcePdf();
        byte[] pdfBytes = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
        when(documentRepository.findByIdInActiveWorkspace("doc_placeholder"))
                .thenReturn(Optional.of(placeholder));
        when(documentRepository.findById(SOURCE_DOCUMENT_ID)).thenReturn(Optional.of(source));
        when(storageProps.getBucket()).thenReturn("fruition-storage");
        when(minioClient.getObject(any())).thenReturn(new GetObjectResponse(
                Headers.of(), "fruition-storage", "us-east-1",
                source.getSourceUri(), new ByteArrayInputStream(pdfBytes)));
        when(converterClient.convertPdf("보고서.pdf", pdfBytes)).thenReturn("# 변환된 본문\n");
        when(editStateRepository.findById("doc_placeholder")).thenReturn(Optional.of(
                new DocumentEditState("doc_placeholder", "PDF 변환 중...\n", "placeholder-hash")));
        when(mongoDocumentEditStore.save(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyLong(), any(DocumentEditState.class)))
                .thenAnswer(invocation -> {
                    DocumentEditState base = invocation.getArgument(8);
                    return new MongoDocumentEditSaveResult(
                            invocation.getArgument(4),
                            base.getMarkdown(),
                            base.getContentHash(),
                            invocation.<Long>getArgument(4) + 1,
                            invocation.getArgument(3),
                            Instant.now(),
                            invocation.getArgument(6),
                            true);
                });

        documentService.doConvert(7L, "doc_placeholder", SOURCE_DOCUMENT_ID);

        verify(mongoDocumentEditStore).save(
                eq(WORKSPACE_ID), eq("doc_placeholder"), eq("# 변환된 본문\n"), anyString(),
                eq(1L), eq("convert:7"), eq(USER_ID), anyLong(), any(DocumentEditState.class));
        // base(1)와 result(2) 두 버전이 read model로 projection된다.
        verify(contentVersionRepository).insertIfAbsent(
                eq("doc_placeholder"), eq(1L), anyString(), anyString(), eq(USER_ID), any());
        verify(contentVersionRepository).insertIfAbsent(
                eq("doc_placeholder"), eq(2L), eq("# 변환된 본문\n"), anyString(), eq(USER_ID), any());
        assertThat(placeholder.getStatus()).isEqualTo(DocumentStatus.completed);
        assertThat(placeholder.getProcessedAt()).isNotNull();
        assertThat(placeholder.getByteSize())
                .isEqualTo("# 변환된 본문\n".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("변환기 호출이 실패하면 placeholder 문서를 failed로 반영하고 원인을 남긴다")
    void doConvert_converterFailure_marksFailed() throws Exception {
        Document placeholder = placeholderDocument();
        Document source = sourcePdf();
        when(documentRepository.findByIdInActiveWorkspace("doc_placeholder"))
                .thenReturn(Optional.of(placeholder));
        when(documentRepository.findById(SOURCE_DOCUMENT_ID)).thenReturn(Optional.of(source));
        when(storageProps.getBucket()).thenReturn("fruition-storage");
        when(minioClient.getObject(any())).thenReturn(new GetObjectResponse(
                Headers.of(), "fruition-storage", "us-east-1",
                source.getSourceUri(), new ByteArrayInputStream(new byte[]{1})));
        when(converterClient.convertPdf(anyString(), any()))
                .thenThrow(new DocumentConvertException("변환기 호출이 실패했습니다. status=422"));

        documentService.doConvert(7L, "doc_placeholder", SOURCE_DOCUMENT_ID);

        assertThat(placeholder.getStatus()).isEqualTo(DocumentStatus.failed);
        assertThat(placeholder.getErrorMessage()).contains("status=422");
        verify(mongoDocumentEditStore, never()).save(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyLong(), any(DocumentEditState.class));
    }

    @Test
    @DisplayName("원본 문서가 사라졌으면 placeholder 문서를 failed로 반영한다")
    void doConvert_missingSource_marksFailed() {
        Document placeholder = placeholderDocument();
        when(documentRepository.findByIdInActiveWorkspace("doc_placeholder"))
                .thenReturn(Optional.of(placeholder));
        when(documentRepository.findById(SOURCE_DOCUMENT_ID)).thenReturn(Optional.empty());

        documentService.doConvert(7L, "doc_placeholder", SOURCE_DOCUMENT_ID);

        assertThat(placeholder.getStatus()).isEqualTo(DocumentStatus.failed);
        assertThat(placeholder.getErrorMessage()).contains(SOURCE_DOCUMENT_ID);
    }

    private Document placeholderDocument() {
        Document placeholder = new Document("doc_placeholder", WORKSPACE_ID, USER_ID, "보고서.md",
                "text/markdown", 15L, null, null, "convert");
        placeholder.initializeConvertPlaceholder(SOURCE_DOCUMENT_ID, null, "placeholder-hash", 15L, 0L);
        return placeholder;
    }
}
