package fruition.core.query.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatMessageRelatedPage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.domain.SourceRef;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.query.exception.PipelineQueryException;
import fruition.core.query.repository.PipelineQueryRequester;
import fruition.core.query.repository.PipelineQueryResponse;
import fruition.core.query.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final String REFERENCE_TYPE_SOURCE_BLOCK = "source_block";

    private final PipelineQueryRequester pipelineQueryClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final ChatMessageRelatedPageRepository relatedPageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final QueryMessageRecorder queryMessageRecorder;

    public QueryService(PipelineQueryRequester pipelineQueryClient,
                        ChatMessageRepository chatMessageRepository,
                        ChatMessageReferenceRepository referenceRepository,
                        ChatMessageRelatedPageRepository relatedPageRepository,
                        ChatSessionRepository chatSessionRepository,
                        QueryMessageRecorder queryMessageRecorder) {
        this.pipelineQueryClient = pipelineQueryClient;
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.queryMessageRecorder = queryMessageRecorder;
    }

    public QueryResponse query(String workspaceId, String sessionId, String question) {
        return query(workspaceId, sessionId, question, "openai", "gpt-5-nano");
    }

    public QueryResponse query(String workspaceId, String sessionId, String question,
                               String provider, String model) {
        return query(workspaceId, sessionId, question, provider, model, false);
    }

    public QueryResponse query(String workspaceId, String sessionId, String question,
                               String provider, String model, boolean webSearchEnabled) {
        QueryMessageContext messageContext = prepareMessages(
                sessionId, question, null, provider, model, webSearchEnabled);
        return querySynchronously(
                workspaceId, sessionId, question, provider, model, webSearchEnabled, messageContext);
    }

    public QueryMessageContext prepareMessages(String sessionId, String question, String requestId) {
        return prepareMessages(sessionId, question, requestId, "openai", "gpt-5-nano");
    }

    public QueryMessageContext prepareMessages(String sessionId, String question, String requestId,
                                               String provider, String model) {
        return prepareMessages(sessionId, question, requestId, provider, model, false);
    }

    public QueryMessageContext prepareMessages(String sessionId, String question, String requestId,
                                               String provider, String model, boolean webSearchEnabled) {
        Instant createdAt = Instant.now();
        QueryMessageContext context = new QueryMessageContext(
                UUID.randomUUID().toString(),
                "chat_user_" + UUID.randomUUID(),
                "chat_assistant_" + UUID.randomUUID(),
                createdAt
        );
        log.info("[질의 메시지 ID 생성] requestId={} pairId={} userMessageId={} assistantMessageId={}",
                requestId, context.pairId(), context.userMessageId(), context.assistantMessageId());
        if (webSearchEnabled) {
            queryMessageRecorder.createPendingPair(
                    sessionId, context.pairId(), context.userMessageId(), context.assistantMessageId(), question,
                    createdAt, provider, model, true);
        } else {
            queryMessageRecorder.createPendingPair(
                    sessionId, context.pairId(), context.userMessageId(), context.assistantMessageId(), question,
                    createdAt, provider, model);
        }
        log.info("[질의 메시지 선저장 commit 완료] requestId={} pairId={} userStatus=completed assistantStatus=pending",
                requestId, context.pairId());
        return context;
    }

    private QueryResponse querySynchronously(String workspaceId,
                                             String sessionId,
                                             String question,
                                             String provider,
                                             String model,
                                             boolean webSearchEnabled,
                                             QueryMessageContext messageContext) {
        log.info("[질의 처리 시작] sessionId={} questionLength={}", sessionId, question.length());
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
        log.info("[질의 세션 확인] sessionId={} workspaceId={} userId={}",
                sessionId, session.getWorkspaceId(), session.getUserId());

        String pairId = messageContext.pairId();
        String assistantMessageId = messageContext.assistantMessageId();

        try {
            log.info("[질의 파이프라인 호출 시작] sessionId={}", sessionId);
            PipelineQueryResponse pipelineResponse = webSearchEnabled
                    ? pipelineQueryClient.query(workspaceId, question, provider, model, true)
                    : pipelineQueryClient.query(workspaceId, question, provider, model);
            log.info("[질의 파이프라인 응답 수신] answerLength={} relatedPageCount={} evidenceCount={} traversalPathCount={}",
                    pipelineResponse.answer() != null ? pipelineResponse.answer().length() : 0,
                    pipelineResponse.relatedPages() != null ? pipelineResponse.relatedPages().size() : 0,
                    pipelineResponse.evidenceSnippets() != null ? pipelineResponse.evidenceSnippets().size() : 0,
                    pipelineResponse.traversalPaths() != null ? pipelineResponse.traversalPaths().size() : 0);

            return completeMessages(session, question, null, messageContext, pipelineResponse);
        } catch (PipelineQueryException e) {
            String errorBody = e.getPipelineErrorBody();
            String errorMessage = errorBody != null
                    ? errorBody.substring(0, Math.min(errorBody.length(), 255))
                    : e.getMessage();
            log.warn("[질의 파이프라인 실패 반영] requestId={} pairId={} errorCode={} errorMessage={}",
                    null, pairId, e.getErrorCode(), errorMessage);
            markAssistantFailed(null, pairId, assistantMessageId, errorMessage, e);
            throw e;
        } catch (Exception e) {
            log.error("[질의 처리 예상 밖 실패 반영] pairId={}", pairId, e);
            markAssistantFailed(null, pairId, assistantMessageId, "질의 처리 중 오류가 발생했습니다.", e);
            throw e;
        }
    }

    /** Kafka Query 결과를 pending assistant 메시지와 참조에 반영한다. */
    @Transactional
    public QueryResponse completeAsync(String sessionId,
                                       String question,
                                       String requestId,
                                       QueryMessageContext messageContext,
                                       PipelineQueryResponse pipelineResponse) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
        return completeMessages(session, question, requestId, messageContext, pipelineResponse);
    }

    @Transactional
    public void failAsync(String requestId, QueryMessageContext messageContext, String errorMessage) {
        queryMessageRecorder.markFailed(messageContext.assistantMessageId(), errorMessage);
        log.warn("[질의 Kafka 실패 반영] requestId={} assistantMessageId={} error={}",
                requestId, messageContext.assistantMessageId(), errorMessage);
    }

    private QueryResponse completeMessages(ChatSession session,
                                           String question,
                                           String requestId,
                                           QueryMessageContext messageContext,
                                           PipelineQueryResponse pipelineResponse) {
        ChatMessage assistantMessage = chatMessageRepository.findById(messageContext.assistantMessageId())
                .orElseThrow(() -> new IllegalStateException(
                        "처리 중인 assistant 메시지를 찾을 수 없습니다: "
                                + messageContext.assistantMessageId()));
        assistantMessage.complete(pipelineResponse.answer());
        chatMessageRepository.save(assistantMessage);

        List<ChatMessageReference> references = buildReferences(assistantMessage, pipelineResponse);
        List<ChatMessageRelatedPage> relatedPages = buildRelatedPages(assistantMessage, pipelineResponse);
        referenceRepository.saveAll(references);
        relatedPageRepository.saveAll(relatedPages);
        touchSessionLastMessageAt(session);
        log.info("[질의 결과 저장 완료] requestId={} pairId={} referenceCount={} relatedPageCount={}",
                requestId, messageContext.pairId(), references.size(), relatedPages.size());

        return new QueryResponse(
                new QueryResponse.MessageSummary(messageContext.userMessageId(), "user", question,
                        "completed", messageContext.createdAt()),
                new QueryResponse.MessageSummary(messageContext.assistantMessageId(), "assistant",
                        pipelineResponse.answer(), "completed", assistantMessage.getCreatedAt()),
                pipelineResponse.relatedPages(), pipelineResponse.evidenceSnippets(),
                pipelineResponse.graphContext(), pipelineResponse.traversalPaths(),
                pipelineResponse.webSearchRequested(), pipelineResponse.webSearchExecuted(),
                pipelineResponse.resultCount(), pipelineResponse.errorCode());
    }

    private void markAssistantFailed(String requestId,
                                     String pairId,
                                     String assistantMessageId,
                                     String errorMessage,
                                     Exception originalException) {
        try {
            queryMessageRecorder.markFailed(assistantMessageId, errorMessage);
            log.info("[질의 assistant 실패 상태 commit 완료] requestId={} pairId={} assistantMessageId={}",
                    requestId, pairId, assistantMessageId);
        } catch (Exception recordException) {
            originalException.addSuppressed(recordException);
            log.error("[질의 assistant 실패 상태 저장 실패] requestId={} pairId={} assistantMessageId={}",
                    requestId, pairId, assistantMessageId, recordException);
        }
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
            if (snippet.sourceDocumentId() == null || snippet.sourceDocumentId().startsWith("web:")
                    || snippet.text() == null || snippet.text().isBlank()) {
                continue;
            }
            refs.add(new ChatMessageReference(
                    assistantMessage, REFERENCE_TYPE_SOURCE_BLOCK,
                    snippet.sourceDocumentId(), snippet.rank(),
                    snippet.sourceBlockIds(), snippet.text(),
                    toDomainSourceRefs(snippet.sourceRefs())
            ));
        }

        return refs;
    }

    private List<SourceRef> toDomainSourceRefs(List<PipelineQueryResponse.SourceRef> sourceRefs) {
        if (sourceRefs == null) return null;
        return sourceRefs.stream()
                .map(r -> new SourceRef(r.sourceDocumentId(), r.sourceBlockId()))
                .toList();
    }

    public record QueryMessageContext(
            String pairId,
            String userMessageId,
            String assistantMessageId,
            Instant createdAt
    ) {}
}
