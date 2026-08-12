package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatWikiExportRequest;
import fruition.core.chat.dto.ChatWikiExportResponse;
import fruition.core.chat.exception.EmptyChatWikiExportException;
import fruition.core.chat.exception.InvalidChatWikiExportRequestException;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.document.service.DocumentService;
import fruition.shared.util.SecretMasker;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(documentService.createChatExportDocument(eq(WS), eq(USER), anyString(), markdown.capture(), anyString(), anyString()))
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
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
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

    @Test
    @DisplayName("이미 위키가 연결된 세션을 full로 다시 export하면 기존 문서를 재생성한다(원본=전체, inline=delta)")
    void fullRegenerationReusesDocumentWithDeltaInline() {
        ChatSession s = session();
        s.linkWikiPage("wiki_page_x");            // 이미 위키 연결됨(재생성 조건)
        s.assignWikiExportDocument("chatdoc_1");  // 재사용할 기존 export 문서

        ChatMessage u1 = msg(s, "u1", "p1", "user", "질문1", "completed");
        ChatMessage a1 = msg(s, "a1", "p1", "assistant", "답변1", "completed");
        u1.markIngested("wiki_page_x");           // p1은 이미 편입됨 → delta 제외
        a1.markIngested("wiki_page_x");
        ChatMessage u2 = msg(s, "u2", "p2", "user", "질문2", "completed");   // p2는 새 문답(미편입)
        ChatMessage a2 = msg(s, "a2", "p2", "assistant", "답변2", "completed");

        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION))
                .thenReturn(List.of(u1, a1, u2, a2));

        ArgumentCaptor<String> full = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> delta = ArgumentCaptor.forClass(String.class);

        ChatWikiExportResponse response = service.export(WS, USER, SESSION, new ChatWikiExportRequest("full", null));

        assertThat(response.status()).isEqualTo("processing");
        assertThat(response.exportDocumentId()).isEqualTo("chatdoc_1");
        verify(documentService).regenerateChatExportDocument(eq("chatdoc_1"), full.capture(), anyString(), delta.capture());
        assertThat(full.getValue()).contains("[session_1:p1]").contains("[session_1:p2]");   // 원본은 전체
        assertThat(delta.getValue()).contains("[session_1:p2]").doesNotContain("[session_1:p1]"); // inline은 delta만
        verify(documentService, never()).createChatExportDocument(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("partial export는 세션의 wikiExportDocumentId를 덮어쓰지 않는다(full 재생성 오염 방지)")
    void partialDoesNotTouchSessionExportDocument() {
        ChatSession s = session();
        s.assignWikiExportDocument("chatdoc_full");   // 기존 full export 문서
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_partial", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest("partial", List.of("p1")));

        assertThat(s.getWikiExportDocumentId()).isEqualTo("chatdoc_full"); // partial이 덮어쓰지 않음
        verify(chatSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 안정 hash여도 partial과 full은 mode별 정식 문서를 만든다")
    void partialAndFullWithSameHashUseSeparateDocuments() {
        ChatSession s = session();
        List<ChatMessage> messages = List.of(
                msg(s, "u1", "p1", "user", "질문1", "completed"),
                msg(s, "a1", "p1", "assistant", "답변1", "completed")
        );
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(messages);
        when(documentService.createChatExportDocument(eq(WS), eq(USER), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new DocumentService.ExportDocumentResult(
                        "chatdoc_" + invocation.<String>getArgument(5), false));

        ChatWikiExportResponse partial = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest("partial", List.of("p1")));
        ChatWikiExportResponse full = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest("full", null));

        assertThat(partial.exportDocumentId()).isEqualTo("chatdoc_partial");
        assertThat(full.exportDocumentId()).isEqualTo("chatdoc_full");
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> modes = ArgumentCaptor.forClass(String.class);
        verify(documentService, times(2)).createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), hashes.capture(), modes.capture());
        assertThat(hashes.getAllValues()).hasSize(2).containsOnly(hashes.getAllValues().get(0));
        assertThat(modes.getAllValues()).containsExactly("partial", "full");
    }

    @Test
    @DisplayName("동일 mode replay는 기존 문서를 재사용한다")
    void sameModeReplayReusesExistingDocument() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), eq("full")))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_full", false))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_full", true));

        ChatWikiExportResponse first = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest("full", null));
        ChatWikiExportResponse replay = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest("full", null));

        assertThat(first).isEqualTo(new ChatWikiExportResponse("chatdoc_full", "processing"));
        assertThat(replay).isEqualTo(new ChatWikiExportResponse("chatdoc_full", "skipped"));
        verify(documentService, times(2)).createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), anyString(), eq("full"));
    }

    @Test
    @DisplayName("partial 이후 full 재생성은 세션의 정식 full 문서를 delta 대상으로 유지한다")
    void partialThenFullRegenerationKeepsFullDocumentTarget() {
        ChatSession s = session();
        s.linkWikiPage("wiki_page_x");
        s.assignWikiExportDocument("chatdoc_full");
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), eq("partial")))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_partial", false));

        ChatWikiExportResponse partial = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest("partial", List.of("p1")));
        ChatWikiExportResponse full = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest("full", null));

        assertThat(partial.exportDocumentId()).isEqualTo("chatdoc_partial");
        assertThat(full.exportDocumentId()).isEqualTo("chatdoc_full");
        verify(documentService).regenerateChatExportDocument(
                eq("chatdoc_full"), anyString(), anyString(), anyString());
        verify(documentService, never()).createChatExportDocument(
                any(), any(), any(), any(), any(), eq("full"));
    }
}
