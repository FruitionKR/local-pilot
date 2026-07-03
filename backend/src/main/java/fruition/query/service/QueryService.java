package fruition.query.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatMessageReference;
import fruition.chat.domain.ChatMessageRelatedPage;
import fruition.chat.domain.ChatSession;
import fruition.chat.exception.ChatSessionNotFoundException;
import fruition.chat.repository.ChatMessageReferenceRepository;
import fruition.chat.repository.ChatMessageRelatedPageRepository;
import fruition.chat.repository.ChatMessageRepository;
import fruition.chat.repository.ChatSessionRepository;
import fruition.query.exception.PipelineQueryException;
import fruition.query.repository.PipelineQueryRequester;
import fruition.query.repository.PipelineQueryResponse;
import fruition.query.dto.QueryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QueryService {

    private static final String REFERENCE_TYPE_SOURCE_BLOCK = "source_block";

    private final PipelineQueryRequester pipelineQueryClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final ChatMessageRelatedPageRepository relatedPageRepository;
    private final ChatSessionRepository chatSessionRepository;

    public QueryService(PipelineQueryRequester pipelineQueryClient,
                        ChatMessageRepository chatMessageRepository,
                        ChatMessageReferenceRepository referenceRepository,
                        ChatMessageRelatedPageRepository relatedPageRepository,
                        ChatSessionRepository chatSessionRepository) {
        this.pipelineQueryClient = pipelineQueryClient;
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    public QueryResponse query(String sessionId, String question) {
        return query(sessionId, question, null, null);
    }

    @Transactional
    public QueryResponse query(String sessionId, String question, String requestId, String logCallbackUrl) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));

        Instant userCreatedAt = Instant.now();

        String pairId = UUID.randomUUID().toString();
        String userMessageId = "chat_user_" + UUID.randomUUID();
        String assistantMessageId = "chat_assistant_" + UUID.randomUUID();

        PipelineQueryResponse pipelineResponse;
        try {
            pipelineResponse = requestId == null
                    ? pipelineQueryClient.query(question)
                    : pipelineQueryClient.query(question, requestId, logCallbackUrl);
        } catch (PipelineQueryException e) {
            String errorBody = e.getPipelineErrorBody();
            String errorMessage = errorBody != null
                    ? errorBody.substring(0, Math.min(errorBody.length(), 255))
                    : e.getMessage();
            chatMessageRepository.saveAll(List.of(
                    new ChatMessage(userMessageId, session, pairId, "user", question, "failed", userCreatedAt, errorMessage),
                    new ChatMessage(assistantMessageId, session, pairId, "assistant", "", "failed", Instant.now(), errorMessage)
            ));
            touchSessionLastMessageAt(session);
            throw e;
        }

        Instant assistantCreatedAt = Instant.now();

        ChatMessage assistantMessage = new ChatMessage(
                assistantMessageId, session, pairId, "assistant", pipelineResponse.answer(), "completed", assistantCreatedAt, null);

        chatMessageRepository.saveAll(List.of(
                new ChatMessage(userMessageId, session, pairId, "user", question, "completed", userCreatedAt, null),
                assistantMessage
        ));

        referenceRepository.saveAll(buildReferences(assistantMessage, pipelineResponse));
        relatedPageRepository.saveAll(buildRelatedPages(assistantMessage, pipelineResponse));
        touchSessionLastMessageAt(session);

        return new QueryResponse(
                new QueryResponse.MessageSummary(userMessageId, "user", question, "completed", userCreatedAt),
                new QueryResponse.MessageSummary(assistantMessageId, "assistant", pipelineResponse.answer(), "completed", assistantCreatedAt),
                pipelineResponse.relatedPages(),
                pipelineResponse.evidenceSnippets(),
                pipelineResponse.graphContext(),
                pipelineResponse.traversalPaths()
        );
    }

    private void touchSessionLastMessageAt(ChatSession session) {
        session.touchLastMessageAt(Instant.now());
        chatSessionRepository.save(session);
    }

    private List<ChatMessageRelatedPage> buildRelatedPages(ChatMessage assistantMessage,
                                                              PipelineQueryResponse pipelineResponse) {
        if (pipelineResponse.relatedPages() == null) return List.of();

        List<ChatMessageRelatedPage> pages = new ArrayList<>();
        List<PipelineQueryResponse.RelatedPage> relatedPages = pipelineResponse.relatedPages();
        for (int i = 0; i < relatedPages.size(); i++) {
            PipelineQueryResponse.RelatedPage rp = relatedPages.get(i);
            pages.add(new ChatMessageRelatedPage(
                    assistantMessage, rp.id(), rp.pageType(), rp.title(), rp.slug(),
                    rp.relevanceScore(), rp.role(), rp.depth(), i + 1
            ));
        }
        return pages;
    }

    private List<ChatMessageReference> buildReferences(ChatMessage assistantMessage,
                                                        PipelineQueryResponse pipelineResponse) {
        if (pipelineResponse.evidenceSnippets() == null) return List.of();

        List<ChatMessageReference> refs = new ArrayList<>();
        for (PipelineQueryResponse.EvidenceSnippet snippet : pipelineResponse.evidenceSnippets()) {
            if (snippet.sourceDocumentId() == null || snippet.text() == null || snippet.text().isBlank()) {
                continue;
            }
            refs.add(new ChatMessageReference(
                    assistantMessage, REFERENCE_TYPE_SOURCE_BLOCK,
                    snippet.sourceDocumentId(), snippet.rank(),
                    snippet.sourceBlockIds(), snippet.text()
            ));
        }

        return refs;
    }
}
