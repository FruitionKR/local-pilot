package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatWikiExportRequest;
import fruition.core.chat.dto.ChatWikiExportResponse;
import fruition.core.chat.exception.EmptyChatWikiExportException;
import fruition.core.chat.exception.InvalidChatWikiExportRequestException;
import fruition.core.chat.repository.ChatMessageRepository;
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
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock DocumentService documentService;

    ChatWikiExportService service;

    @BeforeEach
    void setUp() {
        service = new ChatWikiExportService(chatSessionService, chatMessageRepository,
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

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blockCaptor() {
        return ArgumentCaptor.forClass((Class<List<DocumentService.PipelineSourceBlock>>) (Class<?>) List.class);
    }

    private List<String> blockIds(List<DocumentService.PipelineSourceBlock> blocks) {
        return blocks.stream().map(DocumentService.PipelineSourceBlock::blockId).toList();
    }

    private ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> stubDocumentCreation() {
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = blockCaptor();
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), anyString(), blocks.capture()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));
        return blocks;
    }

    @Test
    @DisplayName("선택한 pair의 문답만 직렬화해 문서로 저장한다")
    void exportsSelectedPairsOnly() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = stubDocumentCreation();

        ChatWikiExportResponse response = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(response.status()).isEqualTo("processing");
        assertThat(response.exportDocumentId()).isEqualTo("chatdoc_1");
        assertThat(blockIds(blocks.getValue())).containsExactly("session_1:p1");
    }

    @Test
    @DisplayName("세션 전체 pair를 넘기면 모든 문답이 한 문서로 들어간다")
    void exportsEveryPairWhenAllSelected() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = stubDocumentCreation();

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1", "p2")));

        assertThat(blockIds(blocks.getValue())).containsExactly("session_1:p1", "session_1:p2");
    }

    @Test
    @DisplayName("제목 없이 저장된 예전 세션도 문서명에 세션 ID를 넣지 않는다")
    void legacyUntitledSessionDoesNotLeakSessionIdIntoFilename() {
        // 기본 제목이 생기기 전에 저장된 세션 상태를 재현한다(생성자는 이제 빈 제목을 채운다).
        ChatSession s = new ChatSession(SESSION, WS, USER, "제목");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "title", null);
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), filename.capture(), anyString(), anyString(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(filename.getValue()).isEqualTo("새 채팅.md").doesNotContain(SESSION);
    }

    @Test
    @DisplayName("skipped 결과는 status=skipped로 반환한다")
    void skippedResultMapsToSkippedStatus() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_existing", true));

        ChatWikiExportResponse response = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(response.status()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("pair_ids가 없거나 비면 400")
    void rejectsMissingPairIds() {
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(null)))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of())))
                .isInstanceOf(InvalidChatWikiExportRequestException.class);
    }

    @Test
    @DisplayName("완전한 문답이 하나도 없으면 400(EmptyChatWikiExport)")
    void rejectsWhenNoCompletePair() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        // user만 completed, assistant는 failed → 완전한 문답 없음
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(List.of(
                msg(s, "u1", "p1", "user", "질문", "completed"),
                msg(s, "a1", "p1", "assistant", "", "failed")
        ));

        assertThatThrownBy(() -> service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1"))))
                .isInstanceOf(EmptyChatWikiExportException.class);
    }

    @Test
    @DisplayName("같은 세션을 다시 내보내도 누적하지 않고 매번 새 문서 생성을 요청한다")
    void reExportAlwaysCreatesAnotherDocument() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_2", false));

        ChatWikiExportResponse first = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));
        ChatWikiExportResponse second = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p2")));

        assertThat(first.exportDocumentId()).isEqualTo("chatdoc_1");
        assertThat(second.exportDocumentId()).isEqualTo("chatdoc_2");
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = blockCaptor();
        verify(documentService, times(2)).createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), anyString(), blocks.capture());
        assertThat(blockIds(blocks.getAllValues().get(0))).containsExactly("session_1:p1");
        assertThat(blockIds(blocks.getAllValues().get(1))).containsExactly("session_1:p2");
    }

    @Test
    @DisplayName("같은 선택을 다시 내보내면 기존 문서를 재사용한다(skipped)")
    void sameSelectionReplayReusesExistingDocument() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        when(documentService.createChatExportDocument(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", true));

        ChatWikiExportResponse first = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));
        ChatWikiExportResponse replay = service.export(
                WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1")));

        assertThat(first).isEqualTo(new ChatWikiExportResponse("chatdoc_1", "processing"));
        assertThat(replay).isEqualTo(new ChatWikiExportResponse("chatdoc_1", "skipped"));
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(documentService, times(2)).createChatExportDocument(
                eq(WS), eq(USER), anyString(), anyString(), hashes.capture(), any());
        assertThat(hashes.getAllValues()).hasSize(2).containsOnly(hashes.getAllValues().get(0));
    }

    @Test
    @DisplayName("본문에는 id를 넣지 않고 블록에만 provenance를 남긴다")
    void keepsProvenanceOutOfMarkdownBody() {
        ChatSession s = session();
        when(chatSessionService.verifyOwnedSession(WS, USER, SESSION)).thenReturn(s);
        when(chatMessageRepository.findAllBySessionIdInTurnOrder(SESSION)).thenReturn(twoCompletedPairs(s));
        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<DocumentService.PipelineSourceBlock>> blocks = blockCaptor();
        when(documentService.createChatExportDocument(
                eq(WS), eq(USER), anyString(), markdown.capture(), anyString(), blocks.capture()))
                .thenReturn(new DocumentService.ExportDocumentResult("chatdoc_1", false));

        service.export(WS, USER, SESSION, new ChatWikiExportRequest(List.of("p1", "p2")));

        assertThat(markdown.getValue()).contains("Q : 질문1").contains("Q : 질문2").doesNotContain("session_1:");
        assertThat(blocks.getValue().get(0).text()).isEqualTo("Q : 질문1\nA : 답변1");
    }
}
