package fruition.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.document.domain.Document;
import fruition.document.domain.DocumentEditState;
import fruition.document.domain.DocumentRole;
import fruition.document.domain.IdempotencyRecord;
import fruition.document.domain.SourceBlock;
import fruition.document.domain.SourceBlockId;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentContentSaveResponse;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentDuplicateResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.dto.DocumentLifecycleRequest;
import fruition.document.dto.DocumentLifecycleResponse;
import fruition.document.dto.DocumentTrashResponse;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.MarkdownDocumentCreateRequest;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.DocumentRenameResponse;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentUploadException;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.exception.DocumentWriteForbiddenException;
import fruition.document.exception.IdempotencyConflictException;
import fruition.document.exception.InvalidIdempotencyKeyException;
import fruition.document.exception.MarkdownContentTooLargeException;
import fruition.document.repository.DocumentProcessingQueueRepository;
import fruition.document.repository.DocumentProcessingRequester;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.repository.IdempotencyRecordRepository;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceBlockRepository;
import fruition.util.StorageProperties;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.WikiPageLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceBlocksTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Mock DocumentRepository documentRepository;
    @Mock fruition.document.repository.FolderRepository folderRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock MinioClient minioClient;
    @Mock StorageProperties storageProps;
    @Mock DocumentProcessingRequester processingRequester;
    @Mock DocumentWikiLinkRepository documentWikiLinkRepository;
    @Mock WikiPageRepository wikiPageRepository;
    @Mock WikiPageLinkRepository wikiPageLinkRepository;
    @Mock SourceBlockRepository sourceBlockRepository;
    @Mock DocumentProcessingQueueRepository queueRepository;
    @Mock TransactionTemplate transactionTemplate;
    @Mock DocumentEditStateInitializer editStateInitializer;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock IdempotencyRecordRepository idempotencyRecordRepository;

    DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, folderRepository, workspaceMemberRepository, minioClient, storageProps,
                processingRequester, documentWikiLinkRepository, wikiPageRepository,
                wikiPageLinkRepository, sourceBlockRepository, queueRepository, transactionTemplate,
                editStateInitializer, editStateRepository, idempotencyRecordRepository,
                new ObjectMapper().findAndRegisterModules(),
                "http://localhost:8080");
    }

    private void stubOwnedWorkspace() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("문서가 존재하면 block 목록을 block_id 오름차순으로 반환한다")
    void blocks_existingDocument_returnsBlocksInOrder() {
        stubOwnedWorkspace();
        Document document = new Document("doc_1f9a74af", WORKSPACE_ID, USER_ID, "original.md", "text/markdown", 100L,
                "sources/documents/doc_1f9a74af/original", "hash1");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1f9a74af", WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(sourceBlockRepository.findAllByIdDocumentIdOrderByIdBlockIdAsc("doc_1f9a74af")).thenReturn(List.of(
                new SourceBlock(new SourceBlockId("doc_1f9a74af", "B0005"), "다섯 번째 block 본문"),
                new SourceBlock(new SourceBlockId("doc_1f9a74af", "B0006"), "여섯 번째 block 본문")
        ));

        DocumentBlocksResponse response = documentService.blocks(WORKSPACE_ID, USER_ID, "doc_1f9a74af");

        assertThat(response.documentId()).isEqualTo("doc_1f9a74af");
        assertThat(response.blocks()).hasSize(2);
        assertThat(response.blocks().get(0).blockId()).isEqualTo("B0005");
        assertThat(response.blocks().get(0).text()).isEqualTo("다섯 번째 block 본문");
        assertThat(response.blocks().get(1).blockId()).isEqualTo("B0006");
    }

    @Test
    @DisplayName("block이 없으면 200과 빈 배열을 반환한다")
    void blocks_noBlocks_returnsEmptyList() {
        stubOwnedWorkspace();
        Document document = new Document("doc_empty", WORKSPACE_ID, USER_ID, "original.md", "text/markdown", 100L,
                "sources/documents/doc_empty/original", "hash2");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_empty", WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(sourceBlockRepository.findAllByIdDocumentIdOrderByIdBlockIdAsc("doc_empty")).thenReturn(List.of());

        DocumentBlocksResponse response = documentService.blocks(WORKSPACE_ID, USER_ID, "doc_empty");

        assertThat(response.blocks()).isEmpty();
    }

    @Test
    @DisplayName("문서가 존재하지 않으면 DocumentNotFoundException을 던진다")
    void blocks_unknownDocument_throws() {
        stubOwnedWorkspace();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_unknown", WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.blocks(WORKSPACE_ID, USER_ID, "doc_unknown"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    @DisplayName("소유하지 않은 워크스페이스면 WorkspaceNotFoundException을 던진다")
    void blocks_notOwnedWorkspace_throws() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> documentService.blocks(WORKSPACE_ID, USER_ID, "doc_1f9a74af"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    @DisplayName("기존 Markdown 상세 조회 시 편집 상태를 lazy 초기화한다")
    void findById_existingMarkdown_initializesEditState() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_lazy",
                WORKSPACE_ID,
                USER_ID,
                "legacy.md",
                "text/markdown",
                10,
                "sources/documents/doc_lazy/original",
                "legacy-hash"
        );
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_lazy", WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(documentWikiLinkRepository.findAllByIdDocumentId("doc_lazy")).thenReturn(List.of());
        when(editStateRepository.findById("doc_lazy"))
                .thenReturn(Optional.of(new DocumentEditState("doc_lazy", "# 제목", "edit-hash")));

        DocumentDetailResponse response = documentService.findById(WORKSPACE_ID, USER_ID, "doc_lazy");

        verify(editStateInitializer).initializeIfNeeded(document);
        assertThat(response.markdown()).isEqualTo("# 제목");
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.editable()).isTrue();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
    }

    @Test
    @DisplayName("호환 목록은 페이지와 원본 자료 메타데이터를 구분해 반환한다")
    void findAll_mapsPageAndSourceMetadata() {
        stubOwnedWorkspace();
        Document page = new Document("doc_page", WORKSPACE_ID, USER_ID, "노트.md", "text/markdown", 10,
                "sources/documents/doc_page/original", "page-hash");
        Document source = new Document("doc_source", WORKSPACE_ID, USER_ID, "자료.pdf", "application/pdf", 20,
                "sources/documents/doc_source/original", "source-hash");
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(page, source));
        when(editStateRepository.findAllById(List.of("doc_page", "doc_source")))
                .thenReturn(List.of(new DocumentEditState("doc_page", "# 노트", "edit-hash")));

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, null);

        assertThat(response.documents()).extracting(DocumentListResponse.DocumentItem::area)
                .containsExactly("pages", "sources");
        assertThat(response.documents()).extracting(DocumentListResponse.DocumentItem::itemKind)
                .containsExactly("page", "source_file");
        assertThat(response.documents().get(0).editable()).isTrue();
        assertThat(response.documents().get(0).fileType()).isEqualTo("md");
        assertThat(response.documents().get(1).editable()).isFalse();
        assertThat(response.documents().get(1).fileType()).isEqualTo("pdf");
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 제거해 파일명 검색 repository에 전달한다")
    void findAll_withQuery_usesFilenameSearch() {
        stubOwnedWorkspace();
        when(documentRepository.searchVisibleByWorkspaceId(WORKSPACE_ID, "보고서")).thenReturn(List.of());

        DocumentListResponse response = documentService.findAll(WORKSPACE_ID, USER_ID, "  보고서  ");

        assertThat(response.documents()).isEmpty();
        verify(documentRepository).searchVisibleByWorkspaceId(WORKSPACE_ID, "보고서");
    }

    @Test
    @DisplayName("초기 노트는 MinIO 없이 직접 생성 Markdown과 편집 상태로 저장한다")
    void createInitialNote_savesDirectMarkdownWithoutMinio() throws Exception {
        documentService.createInitialNote("ws_first", USER_ID);
        documentService.createInitialNote("ws_second", USER_ID);

        ArgumentCaptor<Document> documents = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<DocumentEditState> editStates = ArgumentCaptor.forClass(DocumentEditState.class);
        verify(documentRepository, times(2)).save(documents.capture());
        verify(editStateRepository, times(2)).save(editStates.capture());
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        assertThat(documents.getAllValues())
                .allSatisfy(document -> {
                    assertThat(document.getFilename()).isEqualTo("새 노트.md");
                    assertThat(document.getMimeType()).isEqualTo("text/markdown");
                    assertThat(document.getByteSize()).isPositive();
                    assertThat(document.getSourceUri()).isNull();
                    assertThat(document.getContentHash()).isNull();
                    assertThat(document.getCurrentVersion()).isEqualTo(1);
                    assertThat(document.getStatus()).isEqualTo(fruition.document.domain.DocumentStatus.completed);
                });
        assertThat(documents.getAllValues().get(0).getWorkspaceId()).isEqualTo("ws_first");
        assertThat(documents.getAllValues().get(1).getWorkspaceId()).isEqualTo("ws_second");
        assertThat(editStates.getAllValues())
                .extracting(DocumentEditState::getMarkdown)
                .containsOnly("# 새 노트\n");
    }

    @Test
    @DisplayName("빈 Markdown 직접 생성은 version 1 편집 문서와 멱등 기록을 저장한다")
    void createMarkdown_emptyBody_createsEditableDocument() {
        stubOwnedWorkspace();
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(-1L);

        DocumentUploadResponse response = documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                "create-key",
                new MarkdownDocumentCreateRequest(" 새 문서 ", "", null)
        );

        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(document.capture());
        verify(editStateRepository).save(any(DocumentEditState.class));
        verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
        assertThat(document.getValue().getFilename()).isEqualTo("새 문서.md");
        assertThat(document.getValue().getSourceUri()).isNull();
        assertThat(document.getValue().getSortOrder()).isZero();
        assertThat(response.editable()).isTrue();
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
    }

    @Test
    @DisplayName("직접 생성은 멱등 키를 필수로 받고 UTF-8 5MB 초과 본문을 거절한다")
    void createMarkdown_validatesIdempotencyKeyAndBodySize() {
        stubOwnedWorkspace();

        assertThatThrownBy(() -> documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                null,
                new MarkdownDocumentCreateRequest("문서", "", null)
        )).isInstanceOf(InvalidIdempotencyKeyException.class);

        assertThatThrownBy(() -> documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                "create-key",
                new MarkdownDocumentCreateRequest("문서", "a".repeat(5 * 1024 * 1024 + 1), null)
        )).isInstanceOf(MarkdownContentTooLargeException.class);

        verify(documentRepository, never()).save(any(Document.class));
        verify(editStateRepository, never()).save(any(DocumentEditState.class));
    }

    @Test
    @DisplayName("동일한 멱등 키와 요청은 기존 문서를 반환하고 다시 저장하지 않는다")
    void createMarkdown_sameIdempotencyRequest_replaysExistingDocument() {
        stubOwnedWorkspace();
        MarkdownDocumentCreateRequest request = new MarkdownDocumentCreateRequest("문서", "# 본문", null);

        DocumentUploadResponse first =
                documentService.createMarkdown(WORKSPACE_ID, USER_ID, "same-key", request);
        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(record.capture());
        verify(documentRepository).save(any(Document.class));

        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                USER_ID,
                "POST:/api/workspaces/" + WORKSPACE_ID + "/documents/markdown",
                "same-key"
        )).thenReturn(Optional.of(record.getValue()));
        DocumentUploadResponse replay =
                documentService.createMarkdown(WORKSPACE_ID, USER_ID, "same-key", request);

        assertThat(replay).isEqualTo(first);
        verify(documentRepository, times(1)).save(any(Document.class));
        verify(editStateRepository, times(1)).save(any(DocumentEditState.class));
    }

    @Test
    @DisplayName("같은 멱등 키에 다른 생성 요청은 충돌한다")
    void createMarkdown_sameIdempotencyKeyDifferentRequest_conflicts() {
        stubOwnedWorkspace();
        MarkdownDocumentCreateRequest firstRequest = new MarkdownDocumentCreateRequest("문서", "# 본문", null);
        documentService.createMarkdown(WORKSPACE_ID, USER_ID, "same-key", firstRequest);
        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(record.capture());
        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                USER_ID,
                "POST:/api/workspaces/" + WORKSPACE_ID + "/documents/markdown",
                "same-key"
        )).thenReturn(Optional.of(record.getValue()));

        assertThatThrownBy(() -> documentService.createMarkdown(
                WORKSPACE_ID,
                USER_ID,
                "same-key",
                new MarkdownDocumentCreateRequest("다른 문서", "# 본문", null)
        )).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Markdown 업로드는 즉시 편집 상태를 만들고 editable을 반환한다")
    void uploadMarkdown_createsEditStateImmediately() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.EDITABLE)).thenReturn(-1L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "업로드.md", "text/markdown", "# 업로드".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DocumentUploadResponse response =
                documentService.upload(WORKSPACE_ID, USER_ID, "upload-key", null, file);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(editStateRepository).save(any(DocumentEditState.class));
        verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
        assertThat(response.editable()).isTrue();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.EDITABLE);
        assertThat(response.currentVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("비 Markdown 업로드는 원본만 저장하고 파이프라인을 요청하지 않는다")
    void uploadNonMarkdown_storesOriginalWithoutProcessing() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.findMaxRootSortOrder(WORKSPACE_ID, DocumentRole.ORIGINAL)).thenReturn(-1L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        DocumentUploadResponse response =
                documentService.upload(WORKSPACE_ID, USER_ID, "upload-pdf-key", null, file);

        ArgumentCaptor<Document> storedDocument = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(storedDocument.capture());
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(editStateRepository, never()).save(any(DocumentEditState.class));
        verifyNoInteractions(queueRepository);
        assertThat(storedDocument.getValue().getStatus()).isEqualTo(
                fruition.document.domain.DocumentStatus.uploaded);
        assertThat(storedDocument.getValue().getProcessedAt()).isNull();
        assertThat(response.editable()).isFalse();
        assertThat(response.documentRole()).isEqualTo(DocumentRole.ORIGINAL);
    }

    @Test
    @DisplayName("Markdown 본문 저장은 편집 상태와 현재 버전을 함께 갱신한다")
    void saveContent_changed_updatesContentAndVersion() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "original-hash");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "old", DocumentEditingRules.markdown("old").contentHash());
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(documentRepository.updateContentIfVersionMatches(
                eq(document.getId()), eq(WORKSPACE_ID), eq(1L), anyString(), anyLong(), any()))
                .thenReturn(1);

        DocumentContentSaveResponse response = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "# 변경\n", 1L);

        assertThat(response.changed()).isTrue();
        assertThat(response.currentVersion()).isEqualTo(2);
        assertThat(editState.getMarkdown()).isEqualTo("# 변경\n");
        assertThat(editState.getContentHash()).isEqualTo(response.contentHash());
        assertThat(document.getContentHash()).isEqualTo("original-hash");
    }

    @Test
    @DisplayName("동일 Markdown 저장은 버전과 수정 시각을 변경하지 않는다")
    void saveContent_sameMarkdown_returnsNoOp() {
        stubOwnedWorkspace();
        String markdown = "# 동일\n";
        String hash = DocumentEditingRules.markdown(markdown).contentHash();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown",
                markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "sources/documents/doc_edit/original", hash);
        DocumentEditState editState = new DocumentEditState(document.getId(), markdown, hash);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));

        DocumentContentSaveResponse response = documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), markdown, 1L);

        assertThat(response.changed()).isFalse();
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.updatedAt()).isEqualTo(document.getUpdatedAt());
        verify(documentRepository, never()).updateContentIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("오래된 본문 버전과 비소유자 저장은 거절한다")
    void saveContent_rejectsStaleVersionAndNonOwner() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 4,
                "sources/documents/doc_edit/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, USER_ID, document.getId(), "new", 2L))
                .isInstanceOf(DocumentVersionConflictException.class);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, "member_2"))
                .thenReturn(true);
        assertThatThrownBy(() -> documentService.saveContent(
                WORKSPACE_ID, "member_2", document.getId(), "new", 1L))
                .isInstanceOf(DocumentWriteForbiddenException.class);
    }

    @Test
    @DisplayName("워크스페이스 멤버는 다른 소유자의 문서를 읽지만 변경할 수 없다")
    void workspaceMember_readsButCannotMutateOtherOwnersDocument() {
        String memberId = "member_2";
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(
                WORKSPACE_ID, memberId)).thenReturn(true);
        Document document = new Document(
                "doc_owned_by_other", WORKSPACE_ID, USER_ID, "공유 문서.md",
                "text/markdown", 10, null, null, "direct");
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "# 공유 본문", "edit-hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));
        when(documentWikiLinkRepository.findAllByIdDocumentId(document.getId()))
                .thenReturn(List.of());

        DocumentDetailResponse detail =
                documentService.findById(WORKSPACE_ID, memberId, document.getId());

        assertThat(detail.markdown()).isEqualTo("# 공유 본문");
        assertThatThrownBy(() -> documentService.rename(
                WORKSPACE_ID, memberId, document.getId(),
                new DocumentRenameRequest("변경 시도", 1L)))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> documentService.delete(
                WORKSPACE_ID, memberId, document.getId(), "delete-key",
                new DocumentLifecycleRequest(1L)))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        assertThatThrownBy(() -> documentService.restore(
                WORKSPACE_ID, memberId, document.getId(), "restore-key",
                new DocumentLifecycleRequest(1L)))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        verify(documentRepository, never()).renameIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
        verify(documentRepository, never()).softDeleteIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), any(), any());
        verify(documentRepository, never()).restoreIfVersionMatches(
                anyString(), anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("이름 변경은 확장자를 유지하고 본문과 Wiki 제목을 변경하지 않는다")
    void rename_changesOnlyNotionStylePageTitle() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "기존.md", "text/markdown", 10,
                "sources/documents/doc_edit/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));
        when(documentRepository.renameIfVersionMatches(
                eq(document.getId()), eq(WORKSPACE_ID), eq(1L),
                eq("새 제목.md"), eq("새 제목"), eq("새 제목.md"), any()))
                .thenReturn(1);

        DocumentRenameResponse response = documentService.rename(
                WORKSPACE_ID,
                USER_ID,
                document.getId(),
                new DocumentRenameRequest(" 새 제목 ", 1L)
        );

        assertThat(response.changed()).isTrue();
        assertThat(response.filename()).isEqualTo("새 제목.md");
        assertThat(response.displayName()).isEqualTo("새 제목");
        assertThat(response.currentVersion()).isEqualTo(2);
        verifyNoInteractions(documentWikiLinkRepository);
        verifyNoInteractions(wikiPageRepository);
        verifyNoInteractions(editStateRepository);
    }

    @Test
    @DisplayName("동일 이름 변경은 no-op이고 오래된 버전은 거절한다")
    void rename_sameNameNoOpAndStaleVersionConflict() {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_edit", WORKSPACE_ID, USER_ID, "기존.md", "text/markdown", 10,
                "sources/documents/doc_edit/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(document.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(document));

        DocumentRenameResponse noOp = documentService.rename(
                WORKSPACE_ID, USER_ID, document.getId(), new DocumentRenameRequest("기존", 1L));

        assertThat(noOp.changed()).isFalse();
        assertThat(noOp.updatedAt()).isEqualTo(document.getUpdatedAt());
        assertThatThrownBy(() -> documentService.rename(
                WORKSPACE_ID, USER_ID, document.getId(), new DocumentRenameRequest("새 제목", 2L)))
                .isInstanceOf(DocumentVersionConflictException.class);
        verify(documentRepository, never()).renameIfVersionMatches(
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("최신 Markdown을 새 ID와 version 1로 같은 폴더의 마지막에 복제한다")
    void duplicate_copiesLatestMarkdownAtEndOfSameParent() {
        stubOwnedWorkspace();
        java.util.UUID folderId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
        Document source = new Document(
                "doc_source", WORKSPACE_ID, USER_ID, "보고서.md", "text/markdown", 10,
                null, null, "direct");
        source.initializeDuplicate("doc_origin", folderId, "old-hash", 10, 2);
        Document existingCopy = new Document(
                "doc_existing", WORKSPACE_ID, USER_ID, "보고서 복사본.md",
                "text/markdown", 10, null, null, "duplicate");
        existingCopy.initializeDuplicate("doc_source", folderId, "old-hash", 10, 3);
        DocumentEditState sourceEditState = new DocumentEditState(
                source.getId(), "# 최신 본문\n", DocumentEditingRules.markdown("# 최신 본문\n").contentHash());
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(source.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(documentRepository.findSiblingPagesForUpdate(WORKSPACE_ID, folderId))
                .thenReturn(List.of(source, existingCopy));
        when(editStateRepository.findById(source.getId())).thenReturn(Optional.of(sourceEditState));

        DocumentDuplicateResponse response = documentService.duplicate(
                WORKSPACE_ID, USER_ID, source.getId(), "duplicate-key");

        assertThat(response.id()).startsWith("doc_").isNotEqualTo(source.getId());
        assertThat(response.filename()).isEqualTo("보고서 복사본 (2).md");
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.folderId()).isEqualTo(folderId);
        assertThat(response.sourceDocumentId()).isEqualTo(source.getId());
        assertThat(response.sortOrder()).isEqualTo(4);

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<DocumentEditState> editStateCaptor = ArgumentCaptor.forClass(DocumentEditState.class);
        verify(documentRepository).save(documentCaptor.capture());
        verify(editStateRepository).save(editStateCaptor.capture());
        assertThat(documentCaptor.getValue().getSourceUri()).isNull();
        assertThat(documentCaptor.getValue().getContentHash()).isNull();
        assertThat(editStateCaptor.getValue().getMarkdown()).isEqualTo("# 최신 본문\n");
        verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("같은 복제 요청을 재시도하면 최초 문서만 반환한다")
    void duplicate_sameIdempotencyRequest_replaysFirstResult() {
        stubOwnedWorkspace();
        Document source = new Document(
                "doc_source", WORKSPACE_ID, USER_ID, "보고서.md", "text/markdown", 10,
                null, null, "direct");
        DocumentEditState sourceEditState = new DocumentEditState(
                source.getId(), "# 본문\n", DocumentEditingRules.markdown("# 본문\n").contentHash());
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(source.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(source));
        when(documentRepository.findSiblingPagesForUpdate(WORKSPACE_ID, null))
                .thenReturn(List.of(source));
        when(editStateRepository.findById(source.getId())).thenReturn(Optional.of(sourceEditState));

        DocumentDuplicateResponse first = documentService.duplicate(
                WORKSPACE_ID, USER_ID, source.getId(), "same-key");
        ArgumentCaptor<IdempotencyRecord> recordCaptor =
                ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(recordCaptor.capture());
        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                USER_ID,
                "POST:/api/workspaces/" + WORKSPACE_ID + "/documents/duplicate",
                "same-key"
        )).thenReturn(Optional.of(recordCaptor.getValue()));

        DocumentDuplicateResponse replay = documentService.duplicate(
                WORKSPACE_ID, USER_ID, source.getId(), "same-key");

        assertThat(replay).isEqualTo(first);
        verify(documentRepository, times(1)).save(any(Document.class));
        verify(editStateRepository, times(1)).save(any(DocumentEditState.class));
        verify(idempotencyRecordRepository, times(1)).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("같은 멱등 키를 다른 문서 복제에 사용하면 충돌한다")
    void duplicate_sameIdempotencyKeyForDifferentDocument_conflicts() {
        stubOwnedWorkspace();
        Document firstSource = new Document(
                "doc_first", WORKSPACE_ID, USER_ID, "첫 문서.md", "text/markdown", 10,
                null, null, "direct");
        Document secondSource = new Document(
                "doc_second", WORKSPACE_ID, USER_ID, "둘째 문서.md", "text/markdown", 10,
                null, null, "direct");
        DocumentEditState firstState = new DocumentEditState(
                firstSource.getId(), "# 첫 문서", DocumentEditingRules.markdown("# 첫 문서").contentHash());
        DocumentEditState secondState = new DocumentEditState(
                secondSource.getId(), "# 둘째 문서", DocumentEditingRules.markdown("# 둘째 문서").contentHash());
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(firstSource.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(firstSource));
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(secondSource.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(secondSource));
        when(documentRepository.findSiblingPagesForUpdate(WORKSPACE_ID, null))
                .thenReturn(List.of(firstSource, secondSource));
        when(editStateRepository.findById(firstSource.getId())).thenReturn(Optional.of(firstState));
        when(editStateRepository.findById(secondSource.getId())).thenReturn(Optional.of(secondState));

        documentService.duplicate(WORKSPACE_ID, USER_ID, firstSource.getId(), "reused-key");
        ArgumentCaptor<IdempotencyRecord> recordCaptor =
                ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(recordCaptor.capture());
        when(idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                USER_ID,
                "POST:/api/workspaces/" + WORKSPACE_ID + "/documents/duplicate",
                "reused-key"
        )).thenReturn(Optional.of(recordCaptor.getValue()));

        assertThatThrownBy(() -> documentService.duplicate(
                WORKSPACE_ID, USER_ID, secondSource.getId(), "reused-key"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(documentRepository, times(1)).save(any(Document.class));
    }

    @Test
    @DisplayName("원본 자료와 다른 소유자의 문서는 복제하지 않는다")
    void duplicate_rejectsOriginalAndNonOwner() {
        stubOwnedWorkspace();
        Document original = new Document(
                "doc_pdf", WORKSPACE_ID, USER_ID, "자료.pdf", "application/pdf", 10,
                "sources/documents/doc_pdf/original", "hash");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(original.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> documentService.duplicate(
                WORKSPACE_ID, USER_ID, original.getId(), "duplicate-key"))
                .isInstanceOf(DocumentWriteForbiddenException.class);

        Document otherOwner = new Document(
                "doc_other", WORKSPACE_ID, "user_other", "문서.md", "text/markdown", 10,
                null, null, "direct");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(otherOwner.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(otherOwner));

        assertThatThrownBy(() -> documentService.duplicate(
                WORKSPACE_ID, USER_ID, otherOwner.getId(), "duplicate-key-2"))
                .isInstanceOf(DocumentWriteForbiddenException.class);
        verify(documentRepository, never()).findSiblingPagesForUpdate(anyString(), any());
    }

    @Test
    @DisplayName("문서 삭제는 version을 증가시키고 원본·편집 상태를 보존한다")
    void delete_softDeletesWithoutRemovingDocumentData() throws Exception {
        stubOwnedWorkspace();
        Document document = new Document(
                "doc_delete", WORKSPACE_ID, USER_ID, "문서.md", "text/markdown", 10,
                "sources/documents/doc_delete/original", "original-hash");
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(documentRepository.softDeleteIfVersionMatches(
                eq(document.getId()), eq(WORKSPACE_ID), eq(1L), eq(USER_ID), any(), any()))
                .thenReturn(1);

        DocumentLifecycleResponse response = documentService.delete(
                WORKSPACE_ID,
                USER_ID,
                document.getId(),
                "delete-key",
                new DocumentLifecycleRequest(1L)
        );

        assertThat(response.deleted()).isTrue();
        assertThat(response.currentVersion()).isEqualTo(2);
        verify(documentRepository, never()).delete(any(Document.class));
        verify(queueRepository, never()).deleteByDocumentId(anyString());
        verify(sourceBlockRepository, never()).deleteByIdDocumentId(anyString());
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("삭제 문서는 version이 일치하면 최상위 마지막 위치로 복구된다")
    void restore_deletedDocumentAtEndOfRoot() {
        stubOwnedWorkspace();
        Document deleted = mock(Document.class);
        when(deleted.getId()).thenReturn("doc_restore");
        when(deleted.getUserId()).thenReturn(USER_ID);
        when(deleted.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(deleted.getDeletedAt()).thenReturn(java.time.Instant.now());
        Document root = new Document(
                "doc_root", WORKSPACE_ID, USER_ID, "기존.md", "text/markdown", 10,
                null, null, "direct");
        root.initializeDirectMarkdown("hash", 10, 4);
        when(documentRepository.findByIdAndWorkspaceIdForUpdate(
                deleted.getId(), WORKSPACE_ID)).thenReturn(Optional.of(deleted));
        when(documentRepository.findRootItemsForUpdate(WORKSPACE_ID, DocumentRole.EDITABLE))
                .thenReturn(List.of(root));
        when(documentRepository.restoreIfVersionMatches(
                eq(deleted.getId()), eq(WORKSPACE_ID), eq(2L), eq(5L), any()))
                .thenReturn(1);

        DocumentLifecycleResponse response = documentService.restore(
                WORKSPACE_ID,
                USER_ID,
                deleted.getId(),
                "restore-key",
                new DocumentLifecycleRequest(2L)
        );

        assertThat(response.deleted()).isFalse();
        assertThat(response.currentVersion()).isEqualTo(3);
        assertThat(response.sortOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("휴지통은 삭제 문서만 삭제 시각 역순으로 반환한다")
    void trash_returnsDeletedDocuments() {
        stubOwnedWorkspace();
        Document deleted = mock(Document.class);
        when(deleted.getId()).thenReturn("doc_deleted");
        when(deleted.getFilename()).thenReturn("삭제 문서.md");
        when(deleted.getDisplayName()).thenReturn("삭제 문서");
        when(deleted.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(deleted.getCurrentVersion()).thenReturn(2L);
        when(deleted.getDeletedAt()).thenReturn(java.time.Instant.now());
        when(deleted.getDeletedBy()).thenReturn(USER_ID);
        when(documentRepository.findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                WORKSPACE_ID)).thenReturn(List.of(deleted));

        DocumentTrashResponse response = documentService.trash(WORKSPACE_ID, USER_ID);

        assertThat(response.documents()).hasSize(1);
        assertThat(response.documents().get(0).id()).isEqualTo("doc_deleted");
        assertThat(response.documents().get(0).currentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("MinIO 업로드 실패 시 문서와 멱등 기록을 저장하지 않는다")
    void upload_minioFailure_leavesNoDatabaseState() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("storage failure"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> documentService.upload(WORKSPACE_ID, USER_ID, "upload-key", null, file))
                .isInstanceOf(DocumentUploadException.class);

        verify(documentRepository, never()).save(any(Document.class));
        verify(editStateRepository, never()).save(any(DocumentEditState.class));
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("MinIO 저장 후 DB 실패 시 업로드 객체를 보상 삭제한다")
    void upload_databaseFailure_removesStoredObject() throws Exception {
        stubOwnedWorkspace();
        when(storageProps.getBucket()).thenReturn("test-bucket");
        when(documentRepository.save(any(Document.class))).thenThrow(new RuntimeException("database failure"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "자료.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> documentService.upload(WORKSPACE_ID, USER_ID, "upload-key", null, file))
                .isInstanceOf(DocumentUploadException.class);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("chat_export 문서는 chatWiki=true로 파이프라인 요청을 라우팅한다")
    void doRequestProcessing_chatExport_routesChatWiki() {
        Document chatDoc = new Document("chatdoc_1", WORKSPACE_ID, USER_ID, "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h_chat", "chat_export");
        chatDoc.assignSelectionMode("full");
        when(documentRepository.findByIdInActiveWorkspace("chatdoc_1")).thenReturn(Optional.of(chatDoc));
        when(processingRequester.request(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new DocumentProcessingRequester.PipelineRunResponse("run_1", "running", null, null));

        documentService.doRequestProcessing("chatdoc_1");

        ArgumentCaptor<Boolean> chatWiki = ArgumentCaptor.forClass(Boolean.class);
        verify(processingRequester).request(eq("chatdoc_1"), eq(USER_ID), eq(WORKSPACE_ID), anyString(),
                eq("full"), any(), chatWiki.capture());
        assertThat(chatWiki.getValue()).isTrue();
    }

    @Test
    @DisplayName("일반 업로드 문서는 chatWiki=false로 요청한다")
    void doRequestProcessing_upload_routesGeneric() {
        Document doc = new Document("doc_up", WORKSPACE_ID, USER_ID, "u.pdf", "application/pdf", 10L,
                "sources/documents/doc_up/original", "h_up"); // origin 기본값 "upload"
        when(documentRepository.findByIdInActiveWorkspace("doc_up")).thenReturn(Optional.of(doc));
        when(processingRequester.request(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new DocumentProcessingRequester.PipelineRunResponse("run_2", "running", null, null));

        documentService.doRequestProcessing("doc_up");

        ArgumentCaptor<Boolean> chatWiki = ArgumentCaptor.forClass(Boolean.class);
        verify(processingRequester).request(any(), any(), any(), any(), any(), any(), chatWiki.capture());
        assertThat(chatWiki.getValue()).isFalse();
    }

    @Test
    @DisplayName("삭제 workspace 문서는 새 pipeline 요청을 시작하지 않는다")
    void doRequestProcessing_deletedWorkspace_skipsPipeline() {
        when(documentRepository.findByIdInActiveWorkspace("doc_deleted_workspace"))
                .thenReturn(Optional.empty());

        documentService.doRequestProcessing("doc_deleted_workspace");

        verifyNoInteractions(processingRequester);
    }
}
