package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentEditState;
import fruition.document.domain.DocumentRole;
import fruition.document.domain.SourceBlock;
import fruition.document.domain.SourceBlockId;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentListResponse;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.repository.DocumentProcessingQueueRepository;
import fruition.document.repository.DocumentProcessingRequester;
import fruition.document.repository.DocumentEditStateRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceBlocksTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Mock DocumentRepository documentRepository;
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

    DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, workspaceMemberRepository, minioClient, storageProps,
                processingRequester, documentWikiLinkRepository, wikiPageRepository,
                wikiPageLinkRepository, sourceBlockRepository, queueRepository, transactionTemplate,
                editStateInitializer, editStateRepository,
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
    @DisplayName("초기 노트는 워크스페이스마다 동일 Markdown 문서로 저장한다")
    void createInitialNote_savesIdenticalMarkdownPerWorkspace() throws Exception {
        when(storageProps.getBucket()).thenReturn("test-bucket");

        documentService.createInitialNote("ws_first", USER_ID);
        documentService.createInitialNote("ws_second", USER_ID);

        ArgumentCaptor<Document> documents = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(2)).save(documents.capture());
        verify(minioClient, times(2)).putObject(any(PutObjectArgs.class));
        assertThat(documents.getAllValues())
                .allSatisfy(document -> {
                    assertThat(document.getFilename()).isEqualTo("새 노트.md");
                    assertThat(document.getMimeType()).isEqualTo("text/markdown");
                    assertThat(document.getByteSize()).isPositive();
                });
        // 중복 판별이 (workspace_id, content_hash)로 바뀐 뒤(V5), 초기 노트는 워크스페이스마다
        // 동일한 Markdown을 저장하므로 content_hash도 같다. 저장은 각 워크스페이스로 라우팅된다.
        assertThat(documents.getAllValues().get(0).getWorkspaceId()).isEqualTo("ws_first");
        assertThat(documents.getAllValues().get(1).getWorkspaceId()).isEqualTo("ws_second");
        assertThat(documents.getAllValues().get(0).getContentHash())
                .isEqualTo(documents.getAllValues().get(1).getContentHash());
    }

    @Test
    @DisplayName("chat_export 문서는 chatWiki=true로 파이프라인 요청을 라우팅한다")
    void doRequestProcessing_chatExport_routesChatWiki() {
        Document chatDoc = new Document("chatdoc_1", WORKSPACE_ID, USER_ID, "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h_chat", "chat_export");
        chatDoc.assignSelectionMode("full");
        when(documentRepository.findById("chatdoc_1")).thenReturn(Optional.of(chatDoc));
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
        when(documentRepository.findById("doc_up")).thenReturn(Optional.of(doc));
        when(processingRequester.request(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new DocumentProcessingRequester.PipelineRunResponse("run_2", "running", null, null));

        documentService.doRequestProcessing("doc_up");

        ArgumentCaptor<Boolean> chatWiki = ArgumentCaptor.forClass(Boolean.class);
        verify(processingRequester).request(any(), any(), any(), any(), any(), any(), chatWiki.capture());
        assertThat(chatWiki.getValue()).isFalse();
    }
}
