package fruition.query.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatMessageReference;
import fruition.chat.repository.ChatMessageReferenceRepository;
import fruition.chat.repository.ChatMessageRepository;
import fruition.query.dto.QueryResponse;
import fruition.query.repository.PipelineQueryRequester;
import fruition.query.repository.PipelineQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock PipelineQueryRequester pipelineQueryRequester;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatMessageReferenceRepository referenceRepository;

    QueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new QueryService(pipelineQueryRequester, chatMessageRepository, referenceRepository);
        when(chatMessageRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(referenceRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("파이프라인 응답이 DTO에 담기고 DB에 저장되어 응답으로 반환된다")
    void query_pipelineResponse_savedAndReturned() {
        PipelineQueryResponse mockResponse = samplePipelineResponse();
        when(pipelineQueryRequester.query("Self-Attention이 뭐야?")).thenReturn(mockResponse);

        QueryResponse result = queryService.query("Self-Attention이 뭐야?");

        // 응답 형태 검증
        assertThat(result.assistantMessage().content()).isEqualTo(mockResponse.answer());
        assertThat(result.relatedPages()).hasSize(2);
        assertThat(result.evidenceSnippets()).hasSize(6);
        assertThat(result.graphContext().nodes()).hasSize(2);
        assertThat(result.traversalPaths()).hasSize(1);

        // related_pages role/depth 검증
        assertThat(result.relatedPages().get(0).role()).isEqualTo("seed_source");
        assertThat(result.relatedPages().get(0).depth()).isEqualTo(0);
        assertThat(result.relatedPages().get(1).role()).isEqualTo("focus_concept");

        // chat_messages 저장 검증
        ArgumentCaptor<List<ChatMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageRepository).saveAll(msgCaptor.capture());
        List<ChatMessage> savedMessages = msgCaptor.getValue();
        assertThat(savedMessages).hasSize(2);
        assertThat(savedMessages.get(0).getRole()).isEqualTo("user");
        assertThat(savedMessages.get(1).getRole()).isEqualTo("assistant");

        // chat_message_references 저장 검증
        ArgumentCaptor<List<ChatMessageReference>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(referenceRepository).saveAll(refCaptor.capture());
        List<ChatMessageReference> savedRefs = refCaptor.getValue();

        // source 3개 + concept 3개 = 6개 (related_pages는 저장하지 않음)
        assertThat(savedRefs).hasSize(6);

        long sourceRefs  = savedRefs.stream().filter(r -> "source".equals(r.getReferenceType())).count();
        long conceptRefs = savedRefs.stream().filter(r -> "concept".equals(r.getReferenceType())).count();
        assertThat(sourceRefs).isEqualTo(3);
        assertThat(conceptRefs).isEqualTo(3);

        // source ref: wiki_page_id = 전체 page_id, rank·sentence_index 저장 검증
        ChatMessageReference sourceRef = savedRefs.stream()
                .filter(r -> "source".equals(r.getReferenceType()) && r.getRank() == 1)
                .findFirst().orElseThrow();
        assertThat(sourceRef.getWikiPageId()).isEqualTo("source:codex-container-llm-wiki-api-20260611_013043");
        assertThat(sourceRef.getRank()).isEqualTo(1);
        assertThat(sourceRef.getPageRole()).isEqualTo("seed_source");
        assertThat(sourceRef.getSentenceIndex()).isEqualTo(4);
        assertThat(sourceRef.getQuote()).isNotBlank();

        // concept ref: wiki_page_id·quote 저장 검증
        ChatMessageReference conceptRef = savedRefs.stream()
                .filter(r -> "concept".equals(r.getReferenceType()) && r.getRank() == 2)
                .findFirst().orElseThrow();
        assertThat(conceptRef.getWikiPageId()).isEqualTo("concept:index-md");
        assertThat(conceptRef.getRank()).isEqualTo(2);
        assertThat(conceptRef.getQuote()).isNotBlank();
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
                new PipelineQueryResponse.EvidenceSnippet(sourceId, "source", "LLM Wiki",
                        "codex-container-llm-wiki-api-20260611_013043",
                        "/api/wiki/pages/" + sourceId, "seed_source",
                        "index.md는 위키 콘텐츠를 카테고리별로 정리한 카탈로그 파일입니다", 3.5, 1, 2, 4),
                new PipelineQueryResponse.EvidenceSnippet(conceptId, "concept", "Index.md",
                        "index-md", "/api/wiki/pages/" + conceptId, "focus_concept",
                        "index.md는 위키 콘텐츠를 카테고리별로 정리한 카탈로그 파일입니다", 3.5, 2, 2, 0),
                new PipelineQueryResponse.EvidenceSnippet(conceptId, "concept", "Index.md",
                        "index-md", "/api/wiki/pages/" + conceptId, "focus_concept",
                        "index.md는 LLM이 쿼리 시 관련 페이지를 찾는 첫 번째 참조 지점입니다", 3.5, 3, 4, 0),
                new PipelineQueryResponse.EvidenceSnippet(conceptId, "concept", "Index.md",
                        "index-md", "/api/wiki/pages/" + conceptId, "focus_concept",
                        "위키 내 모든 페이지를 카테고리별로 정리한 파일입니다", 0.75, 4, 0, 0),
                new PipelineQueryResponse.EvidenceSnippet(sourceId, "source", "LLM Wiki",
                        "codex-container-llm-wiki-api-20260611_013043",
                        "/api/wiki/pages/" + sourceId, "seed_source",
                        "LLM Wiki는 LLM을 활용하여 지속적으로 업데이트되는 개인 지식 베이스입니다", 0.25, 5, 0, 0),
                new PipelineQueryResponse.EvidenceSnippet(sourceId, "source", "LLM Wiki",
                        "codex-container-llm-wiki-api-20260611_013043",
                        "/api/wiki/pages/" + sourceId, "seed_source",
                        "기존 RAG 방식과 달리 위키 형태로 지식을 구조화합니다", 0.25, 6, 0, 1)
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
