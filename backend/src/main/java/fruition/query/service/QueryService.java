package fruition.query.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatMessageReference;
import fruition.chat.repository.ChatMessageReferenceRepository;
import fruition.chat.repository.ChatMessageRepository;
import fruition.query.exception.PipelineQueryException;
import fruition.query.repository.PipelineQueryRequester;
import fruition.query.repository.PipelineQueryResponse;
import fruition.query.dto.QueryResponse;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class QueryService {


    private final PipelineQueryRequester pipelineQueryClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final WikiPageRepository wikiPageRepository;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;

    public QueryService(PipelineQueryRequester pipelineQueryClient,
                        ChatMessageRepository chatMessageRepository,
                        ChatMessageReferenceRepository referenceRepository,
                        WikiPageRepository wikiPageRepository,
                        DocumentWikiLinkRepository documentWikiLinkRepository) {
        this.pipelineQueryClient = pipelineQueryClient;
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
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
        if (pipelineResponse.evidenceSnippets() == null) return List.of();

        List<PipelineQueryResponse.EvidenceSnippet> candidates = pipelineResponse.evidenceSnippets().stream()
                .filter(s -> s.pageId() != null && s.text() != null && !s.text().isBlank())
                .toList();

        if (candidates.isEmpty()) return List.of();

        List<String> pageIds = candidates.stream()
                .map(PipelineQueryResponse.EvidenceSnippet::pageId)
                .distinct()
                .toList();

        Map<String, WikiPage> pageMap = wikiPageRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(WikiPage::getId, p -> p));

        Set<String> pageIdsWithoutMarkdown = pageMap.values().stream()
                .filter(p -> p.getMarkdownUri() == null || p.getMarkdownUri().isBlank())
                .map(WikiPage::getId)
                .collect(Collectors.toSet());

        Set<String> pageIdsWithDocLink = pageIdsWithoutMarkdown.isEmpty()
                ? Set.of()
                : documentWikiLinkRepository.findAllByIdWikiPageIdIn(pageIdsWithoutMarkdown).stream()
                        .map(link -> link.getWikiPageId())
                        .collect(Collectors.toSet());

        List<ChatMessageReference> refs = new ArrayList<>();
        for (PipelineQueryResponse.EvidenceSnippet snippet : candidates) {
            WikiPage page = pageMap.get(snippet.pageId());
            if (page == null) continue;

            boolean viewable = (page.getMarkdownUri() != null && !page.getMarkdownUri().isBlank())
                    || pageIdsWithDocLink.contains(snippet.pageId());
            if (!viewable) continue;

            refs.add(new ChatMessageReference(
                    assistantMessageId, snippet.pageType(),
                    snippet.pageId(), null,
                    snippet.pageRole(),
                    Double.valueOf(snippet.score()), null,
                    snippet.rank(), snippet.paragraphIndex(),
                    snippet.sentenceIndex(), snippet.text()
            ));
        }

        return refs;
    }
}
