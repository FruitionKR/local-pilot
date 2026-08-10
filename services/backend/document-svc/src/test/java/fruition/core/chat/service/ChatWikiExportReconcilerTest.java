package fruition.core.chat.service;

import fruition.core.chat.domain.ChatSession;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                        List.of(new PipelineWikiStateRequester.SourceBlock(
                                "B0001", "[session_1:pair_1]Q : 질문\nA : 답변"))));
        when(chatSessionRepository.findById("session_1"))
                .thenReturn(Optional.of(new ChatSession("session_1", "ws_1", "user_1", "제목")));
        when(chatMessageRepository.findAllBySession_IdAndPairIdIn(eq("session_1"), any())).thenReturn(List.of());

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNotNull();
        verify(documentRepository).save(doc);
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
