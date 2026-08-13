package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.chat.repository.ChatSessionRepository;
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
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWikiExportReconcilerTest {

    @Mock DocumentRepository documentRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatPartialWikiRepository chatPartialWikiRepository;

    ChatWikiExportReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ChatWikiExportReconciler(documentRepository, wikiStateRequester,
                chatSessionRepository, chatMessageRepository, chatPartialWikiRepository);
    }

    private Document chatExportDoc(String mode) {
        Document d = new Document("chatdoc_1", "ws_1", "user_1", "c.md", "text/markdown", 10L,
                "sources/documents/chatdoc_1/original", "h", "chat_export");
        d.assignSelectionMode(mode);
        return d;
    }

    @Test
    @DisplayName("full 후처리 성공 시 reconciled_at을 세팅하고 문서를 저장한다")
    void fullReconciled_setsReconciledAtAndSaves() {
        Document doc = chatExportDoc("full");
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
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        ChatMessage message = new ChatMessage("message_1", session, "pair_1", "user", "질문",
                "completed", Instant.parse("2026-08-12T00:00:00Z"), null);
        when(chatSessionRepository.findById("session_1")).thenReturn(Optional.of(session));
        when(chatMessageRepository.findAllBySession_IdAndPairIdIn(eq("session_1"), any()))
                .thenReturn(List.of(message));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNotNull();
        assertThat(session.getWikiPageId()).isEqualTo("wiki_1");
        assertThat(message.getWikiPageId()).isEqualTo("wiki_1");
        verify(documentRepository).save(doc);
    }

    @Test
    @DisplayName("full의 pair 마커가 일부만 보이면 reconciled_at을 세팅하지 않고 재시도한다")
    void fullReconciled_waitsForAllPairMarkers() {
        Document doc = chatExportDoc("full");
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_a", "Q : 질문\nA : 답변"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_b", "Q : 질문\nA : 답변"))));
        when(chatSessionRepository.findById("session_1")).thenReturn(Optional.of(session));
        when(chatMessageRepository.findAllBySession_IdAndPairIdIn(eq("session_1"), any())).thenReturn(
                List.of(new ChatMessage("message_a", session, "pair_a", "user", "질문",
                        "completed", Instant.parse("2026-08-12T00:00:00Z"), null)));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
    }

    @Test
    @DisplayName("partial 멤버십은 문서 전체 가드가 아니라 pair별로 누락분을 기록한다")
    void partialReconciled_recordsMissingPairsAfterPartialFailure() {
        Document doc = chatExportDoc("partial");
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
    @DisplayName("full delta는 기존 편입 pair를 유지하고 신규 pair를 page에 마킹하며 replay에도 멱등이다")
    void fullDeltaReconciled_marksOnlyNewPairsIdempotently() {
        Document doc = chatExportDoc("full");
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        session.linkWikiPage("wiki_1");
        ChatMessage oldMessage = new ChatMessage("message_old", session, "pair_old", "user", "기존 질문",
                "completed", Instant.parse("2026-08-12T00:00:00Z"), null);
        oldMessage.markIngested("wiki_1");
        ChatMessage newMessage = new ChatMessage("message_new", session, "pair_new", "user", "신규 질문",
                "completed", Instant.parse("2026-08-12T00:01:00Z"), null);
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc), List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(
                        List.of(new PipelineWikiStateRequester.DocumentPage(
                                "wiki_1", "source", "제목", "title", "source_of", 1.0)),
                        List.of(
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_old", "Q : 기존 질문\nA : 기존 답변"),
                                new PipelineWikiStateRequester.SourceBlock("session_1:pair_new", "Q : 신규 질문\nA : 신규 답변"))));
        when(chatSessionRepository.findById("session_1")).thenReturn(Optional.of(session));
        when(chatMessageRepository.findAllBySession_IdAndPairIdIn(eq("session_1"), any()))
                .thenReturn(List.of(oldMessage, newMessage));

        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(oldMessage.getWikiPageId()).isEqualTo("wiki_1");
        assertThat(newMessage.getWikiPageId()).isEqualTo("wiki_1");
        verify(chatMessageRepository, times(1)).saveAll(any());
        verify(documentRepository, times(2)).save(doc);
    }

    @Test
    @DisplayName("partial provenance가 유효한 현재 세션의 멤버십으로 기록된다")
    void partialReconciled_recordsValidBlockIds() {
        Document doc = chatExportDoc("partial");
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
        Document doc = chatExportDoc("full");
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
        verifyNoInteractions(chatSessionRepository, chatMessageRepository);
    }

    @Test
    @DisplayName("foreign session이 나중에 나오면 reconciled_at을 세팅하지 않는다")
    void foreignSessionLater_doesNotReconcile() {
        Document doc = chatExportDoc("partial");
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
        Document doc = chatExportDoc("full");
        when(documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed))
                .thenReturn(List.of(doc));
        when(wikiStateRequester.documentContext("ws_1", "chatdoc_1")).thenReturn(
                new PipelineWikiStateRequester.DocumentWikiContext(List.of(), List.of()));

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
    }
}
