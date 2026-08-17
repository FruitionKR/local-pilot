package fruition.core.query.service;

import fruition.core.chat.service.ChatEvidenceRecorder;
import fruition.core.chat.service.ChatTurnRecorder;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final String REFERENCE_TYPE_SOURCE_BLOCK = "source_block";
    private static final int MAX_RECENT_MESSAGE_CONTENT_LENGTH = 4000;

    private final PipelineQueryRequester pipelineQueryClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final ChatMessageRelatedPageRepository relatedPageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatTurnRecorder chatTurnRecorder;
    private final ChatEvidenceRecorder chatEvidenceRecorder;

    public QueryService(PipelineQueryRequester pipelineQueryClient,
                        ChatMessageRepository chatMessageRepository,
                        ChatMessageReferenceRepository referenceRepository,
                        ChatMessageRelatedPageRepository relatedPageRepository,
                        ChatSessionRepository chatSessionRepository,
                        ChatTurnRecorder chatTurnRecorder,
                        ChatEvidenceRecorder chatEvidenceRecorder) {
        this.pipelineQueryClient = pipelineQueryClient;
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatTurnRecorder = chatTurnRecorder;
        this.chatEvidenceRecorder = chatEvidenceRecorder;
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
                createdAt,
                recentMessages(sessionId)
        );
        log.info("[질의 메시지 ID 생성] requestId={} pairId={} userMessageId={} assistantMessageId={}",
                requestId, context.pairId(), context.userMessageId(), context.assistantMessageId());
        if (webSearchEnabled) {
            chatTurnRecorder.createPendingPair(
                    sessionId, context.pairId(), context.userMessageId(), context.assistantMessageId(), question,
                    createdAt, provider, model, true);
        } else {
            chatTurnRecorder.createPendingPair(
                    sessionId, context.pairId(), context.userMessageId(), context.assistantMessageId(), question,
                    createdAt, provider, model);
        }
        log.info("[질의 메시지 선저장 commit 완료] requestId={} pairId={} userStatus=completed assistantStatus=pending",
                requestId, context.pairId());
        return context;
    }

    private List<PipelineQueryRequester.RecentMessage> recentMessages(String sessionId) {
        List<ChatMessage> messages = chatMessageRepository.findAllBySessionIdInTurnOrder(sessionId);
        Set<String> completePairIds = messages.stream()
                .collect(Collectors.groupingBy(ChatMessage::getPairId))
                .entrySet().stream()
                .filter(pair -> pair.getValue().size() == 2)
                .filter(pair -> pair.getValue().stream().allMatch(message -> "completed".equals(message.getStatus())))
                .filter(pair -> pair.getValue().stream().filter(message -> "user".equals(message.getRole())).count() == 1)
                .filter(pair -> pair.getValue().stream().filter(message -> "assistant".equals(message.getRole())).count() == 1)
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
        List<PipelineQueryRequester.RecentMessage> completedMessages = messages.stream()
                .filter(message -> completePairIds.contains(message.getPairId()))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt)
                        .thenComparing(ChatMessage::getPairId)
                        .thenComparingInt(message -> "user".equals(message.getRole()) ? 0 : 1))
                .map(message -> new PipelineQueryRequester.RecentMessage(message.getRole(),
                        message.getContent().substring(0,
                                Math.min(message.getContent().length(), MAX_RECENT_MESSAGE_CONTENT_LENGTH))))
                .toList();
        return completedMessages.subList(Math.max(0, completedMessages.size() - 6), completedMessages.size());
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
                    ? pipelineQueryClient.query(workspaceId, question, provider, model, true,
                    messageContext.recentMessages())
                    : pipelineQueryClient.query(workspaceId, question, provider, model, false,
                    messageContext.recentMessages());
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
        chatTurnRecorder.markFailed(messageContext.assistantMessageId(), errorMessage);
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

        chatEvidenceRecorder.record(assistantMessage, pipelineResponse);
        touchSessionLastMessageAt(session);
        log.info("[질의 결과 저장 완료] requestId={} pairId={}", requestId, messageContext.pairId());

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
            chatTurnRecorder.markFailed(assistantMessageId, errorMessage);
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




    public record QueryMessageContext(
            String pairId,
            String userMessageId,
            String assistantMessageId,
            Instant createdAt,
            @com.fasterxml.jackson.annotation.JsonProperty("recent_messages")
            List<PipelineQueryRequester.RecentMessage> recentMessages
    ) {
        public QueryMessageContext(String pairId, String userMessageId, String assistantMessageId,
                                   Instant createdAt) {
            this(pairId, userMessageId, assistantMessageId, createdAt, List.of());
        }
    }
}
