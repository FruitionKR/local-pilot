package fruition.query.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatMessageReference;
import fruition.chat.repository.ChatMessageReferenceRepository;
import fruition.chat.repository.ChatMessageRepository;
import fruition.query.exception.PipelineQueryException;
import fruition.query.repository.PipelineQueryRequester;
import fruition.query.repository.PipelineQueryResponse;
import fruition.query.dto.QueryResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QueryService {


    private final PipelineQueryRequester pipelineQueryClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;

    public QueryService(PipelineQueryRequester pipelineQueryClient,
                        ChatMessageRepository chatMessageRepository,
                        ChatMessageReferenceRepository referenceRepository) {
        this.pipelineQueryClient = pipelineQueryClient;
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
    }

    public QueryResponse query(String question) {
        Instant userCreatedAt = Instant.now();

        String userMessageId = "chat_user_" + UUID.randomUUID();
        String assistantMessageId = "chat_assistant_" + UUID.randomUUID();

        PipelineQueryResponse pipelineResponse;
        try {
            pipelineResponse = pipelineQueryClient.query(question);
        } catch (PipelineQueryException e) {
            String errorBody = e.getPipelineErrorBody();
            String errorMessage = errorBody != null
                    ? errorBody.substring(0, Math.min(errorBody.length(), 255))
                    : e.getMessage();
            chatMessageRepository.saveAll(List.of(
                    new ChatMessage(userMessageId, "user", question, "failed", userCreatedAt, errorMessage),
                    new ChatMessage(assistantMessageId, "assistant", "", "failed", Instant.now(), errorMessage)
            ));
            throw e;
        }

        Instant assistantCreatedAt = Instant.now();

        chatMessageRepository.saveAll(List.of(
                new ChatMessage(userMessageId, "user", question, "completed", userCreatedAt, null),
                new ChatMessage(assistantMessageId, "assistant", pipelineResponse.answer(), "completed", assistantCreatedAt, null)
        ));

        referenceRepository.saveAll(buildReferences(assistantMessageId, pipelineResponse));

        return new QueryResponse(
                new QueryResponse.MessageSummary(userMessageId, "user", question, "completed", userCreatedAt),
                new QueryResponse.MessageSummary(assistantMessageId, "assistant", pipelineResponse.answer(), "completed", assistantCreatedAt),
                pipelineResponse.relatedPages(),
                pipelineResponse.evidenceSnippets(),
                pipelineResponse.graphContext(),
                pipelineResponse.traversalPaths()
        );
    }

    private List<ChatMessageReference> buildReferences(String assistantMessageId,
                                                        PipelineQueryResponse pipelineResponse) {
        List<ChatMessageReference> refs = new ArrayList<>();

        if (pipelineResponse.evidenceSnippets() != null) {
            for (PipelineQueryResponse.EvidenceSnippet snippet : pipelineResponse.evidenceSnippets()) {
                if (snippet.pageId() == null) continue;
                refs.add(new ChatMessageReference(
                        assistantMessageId, snippet.pageType(),
                        snippet.pageId(), null,
                        snippet.pageRole(),
                        Double.valueOf(snippet.score()), null,
                        snippet.rank(), snippet.paragraphIndex(),
                        snippet.sentenceIndex(), snippet.text()
                ));
            }
        }

        return refs;
    }
}
