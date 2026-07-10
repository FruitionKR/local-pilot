package fruition.chat.service;

import fruition.chat.domain.ChatSession;
import fruition.chat.repository.ChatMessageRepository;
import fruition.chat.repository.ChatPartialWikiRepository;
import fruition.chat.repository.ChatSessionRepository;
import fruition.document.domain.Document;
import fruition.document.domain.DocumentStatus;
import fruition.document.domain.SourceBlock;
import fruition.document.domain.SourceBlockId;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceBlockRepository;
import fruition.wiki.domain.DocumentWikiLink;
import fruition.wiki.domain.DocumentWikiRelationType;
import fruition.wiki.repository.DocumentWikiLinkRepository;
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
    @Mock DocumentWikiLinkRepository documentWikiLinkRepository;
    @Mock SourceBlockRepository sourceBlockRepository;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatPartialWikiRepository chatPartialWikiRepository;

    ChatWikiExportReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ChatWikiExportReconciler(documentRepository, documentWikiLinkRepository,
                sourceBlockRepository, chatSessionRepository, chatMessageRepository, chatPartialWikiRepository);
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
        when(sourceBlockRepository.findAllByIdDocumentIdOrderByIdBlockIdAsc("chatdoc_1")).thenReturn(List.of(
                new SourceBlock(new SourceBlockId("chatdoc_1", "B0001"), "[session_1:pair_1]Q : 질문\nA : 답변")));
        DocumentWikiLink link = mock(DocumentWikiLink.class);
        when(link.getWikiPageId()).thenReturn("wiki_1");
        when(documentWikiLinkRepository.findAllByIdDocumentIdAndIdRelationType("chatdoc_1", DocumentWikiRelationType.source_of))
                .thenReturn(List.of(link));
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
        when(sourceBlockRepository.findAllByIdDocumentIdOrderByIdBlockIdAsc("chatdoc_1")).thenReturn(List.of());

        reconciler.reconcile();

        assertThat(doc.getReconciledAt()).isNull();
        verify(documentRepository, never()).save(doc);
    }
}
