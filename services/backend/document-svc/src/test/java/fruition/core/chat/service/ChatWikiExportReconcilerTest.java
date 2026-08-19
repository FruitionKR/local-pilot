package fruition.core.chat.service;

import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWikiExportReconcilerTest {

    @Mock DocumentRepository documentRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;
    @Mock ChatPartialWikiRepository chatPartialWikiRepository;
    @Mock fruition.core.document.service.DocumentService documentService;

    ChatWikiExportReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ChatWikiExportReconciler(
                documentRepository, wikiStateRequester, chatPartialWikiRepository, documentService);
    }

    private Document chatExportDoc() {
        Document d = new Document("chatdoc_1", "ws_1", "user_1", "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h", "chat_export");
        d.assignSelectionMode("partial");
        return d;
    }

    @Test
    @DisplayName("후처리 성공 시 reconciled_at을 세팅하고 문서를 저장한다")
    void reconciled_setsReconciledAtAndSaves() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_1", "Q : 질문\nA : 답변"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair:extra", "extra colon"),
                                new PipelineWikiStateRequester.SourceBlock("session_1 :pair_whitespace", "whitespace"),
                                new PipelineWikiStateRequester.SourceBlock("session_1: pair_whitespace", "whitespace"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_whitespace ", "whitespace"))));

        reconciler.reconcile();

        ArgumentCaptor<ChatPartialWiki> membership = ArgumentCaptor.forClass(ChatPartialWiki.class);
        verify(chatPartialWikiRepository).save(membership.capture());
        assertThat(membership.getValue().getPairId()).isEqualTo("pair_1");
        assertThat(membership.getValue().getWikiPageId()).isEqualTo("wiki_1");
        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository).save(doc);
    }

    @Test
    @DisplayName("후처리 시 문서 이름을 Wiki 페이지 제목으로 확정한다")
    void reconciled_confirmsDocumentNameWithWikiPageTitle() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "검색 인덱싱", "search-indexing", "source_of", 1.0)),
                        List.of(new PipelineWikiStateRequester.SourceBlock("session_1:pair_1", "Q : 질문\nA : 답변"))));

        reconciler.reconcile();

        verify(documentService).confirmChatExportName(doc, "검색 인덱싱");
    }

    @Test
    @DisplayName("멤버십은 문서 전체 가드가 아니라 pair별로 누락분을 기록한다")
    void reconciled_recordsMissingPairsAfterPartialFailure() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_a", "Q : 질문\nA : 답변"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_c", "Q : 질문\nA : 답변"))));
        when(chatPartialWikiRepository.existsByDocumentIdAndPairId("chatdoc_1", "pair_a")).thenReturn(true);
        when(chatPartialWikiRepository.existsByDocumentIdAndPairId("chatdoc_1", "pair_c")).thenReturn(false, true);

        reconciler.reconcile();
        reconciler.reconcile();

        ArgumentCaptor<ChatPartialWiki> membership = ArgumentCaptor.forClass(ChatPartialWiki.class);
        verify(chatPartialWikiRepository).save(membership.capture());
        assertThat(membership.getValue().getPairId()).isEqualTo("pair_c");
        assertThat(membership.getValue().getWikiPageId()).isEqualTo("wiki_1");
        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository, times(2)).save(doc);
        verify(chatPartialWikiRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("provenance가 유효한 현재 세션의 멤버십으로 기록된다")
    void reconciled_recordsValidBlockIds() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_valid", "유효"),
                                new PipelineWikiStateRequester.SourceBlock(null, "null block_id"),
                                new PipelineWikiStateRequester.SourceBlock("   ", "blank block_id"),
                                new PipelineWikiStateRequester.SourceBlock("malformed", "구분자 없음"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:", "pair 없음"),
                                new PipelineWikiStateRequester.SourceBlock(":pair_missing_session", "session 없음"))));
        when(chatPartialWikiRepository.existsByDocumentIdAndPairId("chatdoc_1", "pair_valid")).thenReturn(false);

        reconciler.reconcile();

        ArgumentCaptor<ChatPartialWiki> membership = ArgumentCaptor.forClass(ChatPartialWiki.class);
        verify(chatPartialWikiRepository).save(membership.capture());
        assertThat(membership.getValue().getSessionId()).isEqualTo("session_1");
        assertThat(membership.getValue().getPairId()).isEqualTo("pair_valid");
        assertThat(doc.getReconciledAt()).isNotNull();
    }

    @Test
    @DisplayName("foreign session이 먼저 나오면 reconciled_at을 세팅하지 않는다")
    void foreignSessionFirst_doesNotReconcile() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_2:pair_foreign", "외부 세션"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_valid", "현재 세션"))));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
        verifyNoInteractions(chatPartialWikiRepository);
    }

    @Test
    @DisplayName("foreign session이 나중에 나오면 reconciled_at을 세팅하지 않는다")
    void foreignSessionLater_doesNotReconcile() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_valid", "현재 세션"),
                                new PipelineWikiStateRequester.SourceBlock("session_2:pair_foreign", "외부 세션"))));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
        verifyNoInteractions(chatPartialWikiRepository);
    }

    @Test
    @DisplayName("source_blocks가 아직 없어 prefix 파싱 실패면 reconciled_at을 세팅하지 않는다(재시도)")
    void notReady_doesNotReconcile() {
        Document doc = chatExportDoc();
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(List.of(), List.of()));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
    }
}
