package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatSession;
import fruition.chat.dto.ChatWikiExportRequest;
import fruition.chat.dto.ChatWikiExportResponse;
import fruition.chat.exception.EmptyChatWikiExportException;
import fruition.chat.exception.InvalidChatWikiExportRequestException;
import fruition.chat.repository.ChatMessageRepository;
import fruition.chat.repository.ChatSessionRepository;
import fruition.document.service.DocumentService;
import fruition.util.SecretMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 채팅 세션을 Wiki page화하기 위한 export 오케스트레이션.
 *
 * 세션을 Markdown으로 직렬화·마스킹한 뒤, 문서 저장/큐 등록은 {@link DocumentService}에 위임한다.
 * 실제 위키 생성은 기존 문서 ingestion 파이프라인이 담당한다. (docs/spec/chat-to-wiki-contract.md)
 *
 * 완료 후 세션↔source wiki page 연결은, 파이프라인이 DB에 직접 기록하는 완료 상태를
 * {@link ChatWikiExportReconciler}가 폴링으로 감지해 처리한다.
 */
@Service
public class ChatWikiExportService {

    private final ChatSessionService chatSessionService;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatWikiMarkdownSerializer serializer;
    private final SecretMasker secretMasker;
    private final DocumentService documentService;

    public ChatWikiExportService(ChatSessionService chatSessionService,
                                 ChatSessionRepository chatSessionRepository,
                                 ChatMessageRepository chatMessageRepository,
                                 ChatWikiMarkdownSerializer serializer,
                                 SecretMasker secretMasker,
                                 DocumentService documentService) {
        this.chatSessionService = chatSessionService;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.serializer = serializer;
        this.secretMasker = secretMasker;
        this.documentService = documentService;
    }

    /** 세션 전체를 Wiki page화용 Markdown으로 직렬화하고 비밀값을 마스킹해 반환한다. (저장/파이프라인 호출 없음) */
    public String previewMarkdown(String workspaceId, String userId, String sessionId) {
        ChatSession session = chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        List<ChatMessage> messages = chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(sessionId);
        return buildMaskedMarkdown(session, messages);
    }

    /** 선택된(full=전체 / partial=선택 문답) 채팅을 문서로 저장하고 처리 큐에 등록한다. */
    @Transactional
    public ChatWikiExportResponse export(String workspaceId, String userId, String sessionId,
                                         ChatWikiExportRequest request) {
        validate(request);
        ChatSession session = chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        List<ChatMessage> messages = chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(sessionId);
        List<ChatMessage> selected = selectMessages(messages, request);

        String markdown = buildMaskedMarkdown(session, selected);
        if (!markdown.contains("]Q : ")) { // 완전한 문답이 하나도 없음
            throw new EmptyChatWikiExportException(sessionId);
        }
        String contentHash = stableContentHash(session, selected);
        String filename = titleOf(session) + ".md";

        DocumentService.ExportDocumentResult result =
                documentService.createChatExportDocument(workspaceId, userId, filename, markdown, contentHash);

        // 완료 콜백에서 이 세션을 역조회해 wiki_page_id를 연결할 수 있도록 export 문서 id를 기록한다.
        session.assignWikiExportDocument(result.documentId());
        chatSessionRepository.save(session);

        String status = result.skipped() ? "skipped" : "processing";
        return new ChatWikiExportResponse(result.documentId(), status);
    }

    private void validate(ChatWikiExportRequest request) {
        if (request == null || request.selectionMode() == null) {
            throw new InvalidChatWikiExportRequestException("selection_mode는 필수입니다.");
        }
        boolean full = "full".equals(request.selectionMode());
        boolean partial = "partial".equals(request.selectionMode());
        if (!full && !partial) {
            throw new InvalidChatWikiExportRequestException("selection_mode는 full 또는 partial이어야 합니다.");
        }
        if (partial && (request.pairIds() == null || request.pairIds().isEmpty())) {
            throw new InvalidChatWikiExportRequestException("partial 선택은 pair_ids가 필요합니다.");
        }
    }

    /** partial이면 선택된 pair_id의 메시지만, full이면 전체를 반환한다. */
    private List<ChatMessage> selectMessages(List<ChatMessage> messages, ChatWikiExportRequest request) {
        if ("partial".equals(request.selectionMode())) {
            Set<String> selectedPairIds = new HashSet<>(request.pairIds());
            return messages.stream().filter(m -> selectedPairIds.contains(m.getPairId())).toList();
        }
        return messages;
    }

    private String buildMaskedMarkdown(ChatSession session, List<ChatMessage> messages) {
        return secretMasker.mask(serializer.serialize(session, messages));
    }

    /**
     * 재-export 중복 판별용 안정 해시. conversation(session id)과 completed 메시지의 role/content만 기준으로 삼는다. (spec A-1)
     */
    private String stableContentHash(ChatSession session, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder(session.getId());
        for (ChatMessage message : messages) {
            if (!"completed".equals(message.getStatus())) {
                continue;
            }
            sb.append('\n').append(message.getRole()).append('\n').append(message.getContent());
        }
        return sha256(sb.toString());
    }

    private String titleOf(ChatSession session) {
        return (session.getTitle() != null && !session.getTitle().isBlank())
                ? session.getTitle()
                : "채팅 " + session.getId();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}
