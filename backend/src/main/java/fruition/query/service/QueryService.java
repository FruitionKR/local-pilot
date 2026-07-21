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
        return query(workspaceId, sessionId, question, null, null);
    }

    @Transactional
    public QueryResponse query(String workspaceId,
                               String sessionId,
                               String question,
                               String requestId,
                               String logCallbackUrl) {
        QueryMessageContext messageContext = prepareMessages(sessionId, question, requestId);
        return query(workspaceId, sessionId, question, requestId, logCallbackUrl, messageContext);
    }

    public QueryMessageContext prepareMessages(String sessionId, String question, String requestId) {
        Instant createdAt = Instant.now();
        QueryMessageContext context = new QueryMessageContext(
                UUID.randomUUID().toString(),
                "chat_user_" + UUID.randomUUID(),
                "chat_assistant_" + UUID.randomUUID(),
                createdAt
        );
        log.info("[질의 메시지 ID 생성] requestId={} pairId={} userMessageId={} assistantMessageId={}",
                requestId, context.pairId(), context.userMessageId(), context.assistantMessageId());
        queryMessageRecorder.createPendingPair(
                sessionId, context.pairId(), context.userMessageId(), context.assistantMessageId(), question, createdAt);
        log.info("[질의 메시지 선저장 commit 완료] requestId={} pairId={} userStatus=completed assistantStatus=pending",
                requestId, context.pairId());
        return context;
    }

    @Transactional
    public QueryResponse query(String workspaceId,
                               String sessionId,
                               String question,
                               String requestId,
                               String logCallbackUrl,
                               QueryMessageContext messageContext) {
        log.info("[질의 처리 시작] sessionId={} requestId={} async={} questionLength={} callbackUrl={}",
                sessionId, requestId, requestId != null, question.length(), logCallbackUrl);
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
        log.info("[질의 세션 확인] sessionId={} workspaceId={} userId={}",
                sessionId, session.getWorkspaceId(), session.getUserId());

        String pairId = messageContext.pairId();
        String userMessageId = messageContext.userMessageId();
        String assistantMessageId = messageContext.assistantMessageId();
        Instant userCreatedAt = messageContext.createdAt();

        try {
            log.info("[질의 파이프라인 호출 시작] requestId={} callbackEnabled={}",
                    requestId, logCallbackUrl != null);
            PipelineQueryResponse pipelineResponse = requestId == null
                    ? pipelineQueryClient.query(workspaceId, question)
                    : pipelineQueryClient.query(workspaceId, question, requestId, logCallbackUrl);
            log.info("[질의 파이프라인 응답 수신] requestId={} answerLength={} relatedPageCount={} evidenceCount={} traversalPathCount={}",
                    requestId,
                    pipelineResponse.answer() != null ? pipelineResponse.answer().length() : 0,
                    pipelineResponse.relatedPages() != null ? pipelineResponse.relatedPages().size() : 0,
                    pipelineResponse.evidenceSnippets() != null ? pipelineResponse.evidenceSnippets().size() : 0,
                    pipelineResponse.traversalPaths() != null ? pipelineResponse.traversalPaths().size() : 0);

            ChatMessage assistantMessage = chatMessageRepository.findById(assistantMessageId)
                    .orElseThrow(() -> new IllegalStateException(
                            "처리 중인 assistant 메시지를 찾을 수 없습니다: " + assistantMessageId));
            assistantMessage.complete(pipelineResponse.answer());
            chatMessageRepository.save(assistantMessage);
            log.info("[질의 assistant 메시지 완료] requestId={} pairId={} assistantMessageId={}",
                    requestId, pairId, assistantMessageId);

            List<ChatMessageReference> references = buildReferences(assistantMessage, pipelineResponse);
            List<ChatMessageRelatedPage> relatedPages = buildRelatedPages(assistantMessage, pipelineResponse);
            referenceRepository.saveAll(references);
            relatedPageRepository.saveAll(relatedPages);
            log.info("[질의 근거/관련 페이지 DB 저장 완료] requestId={} referenceCount={} relatedPageCount={}",
                    requestId, references.size(), relatedPages.size());
            touchSessionLastMessageAt(session);
            log.info("[질의 처리 완료] requestId={} pairId={}", requestId, pairId);

            return new QueryResponse(
                    new QueryResponse.MessageSummary(userMessageId, "user", question, "completed", userCreatedAt),
                    new QueryResponse.MessageSummary(
                            assistantMessageId, "assistant", pipelineResponse.answer(), "completed", assistantMessage.getCreatedAt()),
                    pipelineResponse.relatedPages(),
                    pipelineResponse.evidenceSnippets(),
                    pipelineResponse.graphContext(),
                    pipelineResponse.traversalPaths()
            );
        } catch (PipelineQueryException e) {
            String errorBody = e.getPipelineErrorBody();
            String errorMessage = errorBody != null
                    ? errorBody.substring(0, Math.min(errorBody.length(), 255))
                    : e.getMessage();
            log.warn("[질의 파이프라인 실패 반영] requestId={} pairId={} errorCode={} errorMessage={}",
                    requestId, pairId, e.getErrorCode(), errorMessage);
            markAssistantFailed(requestId, pairId, assistantMessageId, errorMessage, e);
            throw e;
        } catch (Exception e) {
            log.error("[질의 처리 예상 밖 실패 반영] requestId={} pairId={}", requestId, pairId, e);
            markAssistantFailed(requestId, pairId, assistantMessageId, "질의 처리 중 오류가 발생했습니다.", e);
            throw e;
        }
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

    public record QueryMessageContext(
            String pairId,
            String userMessageId,
            String assistantMessageId,
            Instant createdAt
    ) {}
}
