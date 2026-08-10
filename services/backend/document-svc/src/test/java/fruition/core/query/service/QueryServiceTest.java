package fruition.core.query.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatMessageRelatedPage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.exception.PipelineQueryException;
import fruition.core.query.repository.PipelineQueryRequester;
import fruition.core.query.repository.PipelineQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    private static final String SESSION_ID = "session_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String DOCUMENT_ID = "doc_1f9a74af";

    @Mock PipelineQueryRequester pipelineQueryRequester;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatMessageReferenceRepository referenceRepository;
    @Mock ChatMessageRelatedPageRepository relatedPageRepository;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock QueryMessageRecorder queryMessageRecorder;

    QueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new QueryService(
                pipelineQueryRequester, chatMessageRepository, referenceRepository, relatedPageRepository,
                chatSessionRepository, queryMessageRecorder);
        lenient().when(chatMessageRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        lenient().when(chatMessageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(referenceRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        lenient().when(relatedPageRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        ChatSession session = new ChatSession(SESSION_ID, "ws_aaa11111", "user_1f9a74af", null);
        lenient().when(chatSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        lenient().when(chatMessageRepository.findById(anyString())).thenAnswer(invocation -> Optional.of(
                new ChatMessage(invocation.getArgument(0), session, "pair_abc123", "assistant", "", "pending",
                        java.time.Instant.now(), null)));
        lenient().when(chatSessionRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("파이프라인 응답이 DTO에 담기고 DB에 저장되어 응답으로 반환된다")
    void query_pipelineResponse_savedAndReturned() {
        PipelineQueryResponse mockResponse = samplePipelineResponse();
        when(pipelineQueryRequester.query(WORKSPACE_ID, "Self-Attention이 뭐야?",
                "openai", "gpt-4.1-mini")).thenReturn(mockResponse);

        QueryResponse result = queryService.query(WORKSPACE_ID, SESSION_ID, "Self-Attention이 뭐야?");

        // 응답 형태 검증
        assertThat(result.assistantMessage().content()).isEqualTo(mockResponse.answer());
        assertThat(result.relatedPages()).hasSize(2);
        assertThat(result.evidenceSnippets()).hasSize(2);

        // related_pages role/depth 검증
        assertThat(result.relatedPages().get(0).role()).isEqualTo("seed_source");
        assertThat(result.relatedPages().get(0).depth()).isEqualTo(0);
        assertThat(result.relatedPages().get(1).role()).isEqualTo("focus_concept");

        verify(queryMessageRecorder).createPendingPair(
                eq(SESSION_ID), anyString(), anyString(), anyString(), eq("Self-Attention이 뭐야?"), any(),
                eq("openai"), eq("gpt-4.1-mini"));
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getRole()).isEqualTo("assistant");
        assertThat(messageCaptor.getValue().getStatus()).isEqualTo("completed");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo(mockResponse.answer());

        // chat_message_references 저장 검증 (원본 문서 block 기준)
        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        List<ChatMessageReference> savedRefs = refCaptor.getValue();
        assertThat(savedRefs).hasSize(2);

        ChatMessageReference firstRef = savedRefs.stream()
                .filter(r -> r.getRank() == 1).findFirst().orElseThrow();
        assertThat(firstRef.getReferenceType()).isEqualTo("source_block");
        assertThat(firstRef.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(firstRef.getSourceBlockIds()).isEqualTo(List.of("B0005", "B0006"));
        assertThat(firstRef.getQuote()).isEqualTo("원본 block citation이 붙어 있던 근거 문장");
        // source_refs는 대표 문서(DOCUMENT_ID) 외 다른 문서(doc_cross) block까지 보존한다.
        assertThat(firstRef.getSourceRefs()).containsExactly(
                new fruition.core.chat.domain.SourceRef(DOCUMENT_ID, "B0005"),
                new fruition.core.chat.domain.SourceRef("doc_cross", "B0009"));

        ChatMessageReference secondRef = savedRefs.stream()
                .filter(r -> r.getRank() == 2).findFirst().orElseThrow();
        assertThat(secondRef.getDocumentId()).isEqualTo("doc_2a8b91cc");
        assertThat(secondRef.getSourceBlockIds()).isEqualTo(List.of("B0010"));

        // chat_message_related_pages 저장 검증
        ArgumentCaptor<List<ChatMessageRelatedPage>> rpCaptor = ArgumentCaptor.forClass(List.class);
        verify(relatedPageRepository).saveAll(rpCaptor.capture());
        List<ChatMessageRelatedPage> savedRelatedPages = rpCaptor.getValue();
        assertThat(savedRelatedPages).hasSize(2);
        assertThat(savedRelatedPages.get(0).getRole()).isEqualTo("seed_source");
        assertThat(savedRelatedPages.get(0).getDepth()).isEqualTo(0);
        assertThat(savedRelatedPages.get(0).getRank()).isEqualTo(1);
        assertThat(savedRelatedPages.get(1).getRank()).isEqualTo(2);

        // 세션 last_message_at 갱신 검증
        verify(chatSessionRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("파이프라인 실패 시 pending assistant가 failed로 변경되고 예외가 전파된다")
    void query_pipelineFailure_marksAssistantFailedAndRethrows() {
        PipelineQueryException pipelineError = new PipelineQueryException("PIPELINE_UNAVAILABLE", "pipeline 연결 실패", 503, "{\"error\": \"service unavailable\"}");
        when(pipelineQueryRequester.query(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(pipelineError);

        assertThatThrownBy(() -> queryService.query(WORKSPACE_ID, SESSION_ID, "Self-Attention이 뭐야?"))
                .isInstanceOf(PipelineQueryException.class);

        verify(queryMessageRecorder).createPendingPair(
                eq(SESSION_ID), anyString(), anyString(), anyString(), eq("Self-Attention이 뭐야?"), any(),
                eq("openai"), eq("gpt-4.1-mini"));
        verify(queryMessageRecorder).markFailed(anyString(), eq("{\"error\": \"service unavailable\"}"));
    }

    @Test
    @DisplayName("예상 밖 오류 시 pending assistant가 일반화된 오류로 failed 처리된다")
    void query_unexpectedFailure_marksAssistantFailedWithGeneralMessage() {
        when(pipelineQueryRequester.query(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("DB 연결 종료"));

        assertThatThrownBy(() -> queryService.query(WORKSPACE_ID, SESSION_ID, "질문"))
                .isInstanceOf(IllegalStateException.class);

        verify(queryMessageRecorder).markFailed(anyString(), eq("질의 처리 중 오류가 발생했습니다."));
    }

    @Test
    @DisplayName("세션이 존재하지 않으면 ChatSessionNotFoundException을 던진다")
    void query_unknownSession_throwsChatSessionNotFound() {
        when(chatSessionRepository.findById("session_unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.query(WORKSPACE_ID, "session_unknown", "질문"))
                .isInstanceOf(ChatSessionNotFoundException.class);
    }

    private PipelineQueryResponse samplePipelineResponse() {
        String sourceId = "source:codex-container-llm-wiki-api-20260611_013043";
        String conceptId = "concept:index-md";

        List<PipelineQueryResponse.RelatedPage> relatedPages = List.of(
                new PipelineQueryResponse.RelatedPage(sourceId, "source", "LLM Wiki",
                        "codex-container-llm-wiki-api-20260611_013043", 1.0, "seed_source", 0),
                new PipelineQueryResponse.RelatedPage(conceptId, "concept", "Index.md",
                        "index-md", 1.0, "focus_concept", 1)
        );

        List<PipelineQueryResponse.EvidenceSnippet> evidenceSnippets = List.of(
                new PipelineQueryResponse.EvidenceSnippet(
                        1, DOCUMENT_ID, List.of("B0005", "B0006"),
                        List.of(new PipelineQueryResponse.SourceRef(DOCUMENT_ID, "B0005"),
                                new PipelineQueryResponse.SourceRef("doc_cross", "B0009")),
                        "원본 block citation이 붙어 있던 근거 문장"),
                new PipelineQueryResponse.EvidenceSnippet(
                        2, "doc_2a8b91cc", List.of("B0010"),
                        List.of(new PipelineQueryResponse.SourceRef("doc_2a8b91cc", "B0010")),
                        "두 번째 근거 block 본문")
        );

        PipelineQueryResponse.GraphEdge edge = new PipelineQueryResponse.GraphEdge(
                sourceId, conceptId, "source_mentions_concept", "seed_to_focus", 1.0);
        PipelineQueryResponse.GraphContext graphContext = new PipelineQueryResponse.GraphContext(
                relatedPages, List.of(edge));

        PipelineQueryResponse.TraversalPath traversalPath = new PipelineQueryResponse.TraversalPath(
                "path_1", "primary_answer_path", true, 1.0, "answer_context_selected",
                List.of(sourceId, conceptId), List.of(edge));

        return new PipelineQueryResponse(
                "Index.md는 위키 내 모든 페이지를 카테고리별로 정리한 카탈로그 파일 역할을 합니다. [1]",
                relatedPages, evidenceSnippets, graphContext, List.of(traversalPath)
        );
    }
}
