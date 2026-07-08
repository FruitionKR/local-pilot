package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatSession;
import fruition.chat.dto.ChatWikiExportRequest;
import fruition.chat.dto.ChatWikiExportResponse;
import fruition.chat.exception.EmptyChatWikiExportException;
import fruition.chat.exception.InvalidChatWikiExportRequestException;
import fruition.chat.repository.ChatMessageRepository;
import fruition.chat.repository.ChatSessionRepository;
import fruition.document.service.DocumentService;
import fruition.util.SecretMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWikiExportServiceTest {

    private static final String WS = "ws_1";
    private static final String USER = "user_1";
    private static final String SESSION = "session_1";
    private static final Instant NOW = Instant.parse("2026-07-08T10:00:00Z");

    @Mock ChatSessionService chatSessionService;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock DocumentService documentService;

    ChatWikiExportService service;

    @BeforeEach
    void setUp() {
        service = new ChatWikiExportService(chatSessionService, chatSessionRepository, chatMessageRepository,
                new ChatWikiMarkdownSerializer(), new SecretMasker(), documentService);
    }

    private ChatSession session() {
        return new ChatSession(SESSION, WS, USER, "제목");
    }

    private ChatMessage msg(ChatSession s, String id, String pairId, String role, String content, String status) {
        return new ChatMessage(id, s, pairId, role, content, status, NOW, null);
    }

    private List<ChatMessage> twoCompletedPairs(ChatSession s) {
        return List.of(
                msg(s, "u1", "p1", "user", "질문1", "completed"),
                msg(s, "a1", "p1", "assistant", "답변1", "completed"),
                msg(s, "u2", "p2", "user", "질문2", "completed"),
                msg(s, "a2", "p2", "assistant", "답변2", "completed")
        );
    }

    private ArgumentCaptor<String> stubDocumentCreation() {
        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(eq(WS), eq(USER), anyString(), markdown.capture(), anyString()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));
        return markdown;
    }

    @Test
    @DisplayName("full은 모든 문답을 직렬화해 문서로 저장한다")
    void fullExportsAllPairs() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<String> markdown = stubDocumentCreation();

        ChatWikiExportResponse response = service.export(WS, USER, SESSION, new ChatWikiExportRequest("full", null));

        assertThat(response.status()).isEqualTo("processing");
        assertThat(response.exportDocumentId()).isEqualTo("chatdoc_1");
        assertThat(markdown.getValue()).contains("[session_1:p1]").contains("[session_1:p2]");
        verify(chatSessionRepository).save(s);
    }

    @Test
    @DisplayName("partial은 선택된 pair의 문답만 직렬화한다")
    void partialExportsSelectedPairsOnly() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<String> markdown = stubDocumentCreation();

        service.export(WS, USER, SESSION, new ChatWikiExportRequest("partial", List.of("p1")));

        assertThat(markdown.getValue()).contains("[session_1:p1]").doesNotContain("[session_1:p2]");
    }

    @Test
    @DisplayName("skipped 결과는 status=skipped로 반환한다")
    void skippedResultMapsToSkippedStatus() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_existing", true));

        ChatWikiExportResponse response = service.export(WS, USER, SESSION, new ChatWikiExportRequest("full", null));

        assertThat(response.status()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("selection_mode가 없거나 잘못되면 400")
    void rejectsInvalidSelectionMode() {
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(null, null)))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest("bogus", null)))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
    }

    @Test
    @DisplayName("partial인데 pair_ids가 비면 400")
    void rejectsPartialWithoutPairIds() {
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest("partial", List.of())))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
    }

    @Test
    @DisplayName("완전한 문답이 하나도 없으면 400(EmptyChatWikiExport)")
    void rejectsWhenNoCompletePair() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        // user만 completed, assistant는 failed → 완전한 문답 없음
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "질문", "completed"),
                msg(s, "a1", "p1", "assistant", "", "failed")
        ));

        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest("full", null)))
                .isInstanceOf(EmptyChatWikiExportException.class);
    }
}
