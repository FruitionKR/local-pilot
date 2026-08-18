package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.dto.ChatWikiExportRequest;
import fruition.core.chat.dto.ChatWikiExportResponse;
import fruition.core.chat.exception.EmptyChatWikiExportException;
import fruition.core.chat.exception.InvalidChatWikiExportRequestException;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.service.ChatWikiMarkdownSerializer.ChatSourceBlock;
import fruition.core.chat.service.ChatWikiMarkdownSerializer.ChatWikiSource;
import fruition.core.document.service.DocumentService;
import fruition.shared.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 실제 위키 생성은 기존 문서 ingestion 파이프라인이 담당한다. (docs/backlog/spec/chat-to-wiki-contract.md)
 *
 * export는 언제나 선택한 문답만 담은 새 문서를 만든다. 세션에 누적하거나 기존 문서를 재사용하지 않으므로
 * 같은 세션을 여러 번 내보내면 그때마다 독립 source page가 생긴다.
 *
 * 완료 후 문답↔wiki page 기록은, 파이프라인이 DB에 직접 기록하는 완료 상태를
 * {@link ChatWikiExportReconciler}가 폴링으로 감지해 처리한다.
 */
@Service
public class ChatWikiExportService {

    private static final Logger log = LoggerFactory.getLogger(ChatWikiExportService.class);

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatWikiMarkdownSerializer serializer;
    private final SecretMasker secretMasker;
    private final DocumentService documentService;

    public ChatWikiExportService(ChatSessionService chatSessionService,
                                 ChatMessageRepository chatMessageRepository,
                                 ChatWikiMarkdownSerializer serializer,
                                 SecretMasker secretMasker,
                                 DocumentService documentService) {
        this.chatSessionService = chatSessionService;
        this.chatMessageRepository = chatMessageRepository;
        this.serializer = serializer;
        this.secretMasker = secretMasker;
        this.documentService = documentService;
    }

    /** 세션 전체를 Wiki page화용 Markdown으로 직렬화하고 비밀값을 마스킹해 반환한다. (저장/파이프라인 호출 없음) */
    public String previewMarkdown(String workspaceId, String userId, String sessionId) {
        ChatSession session = chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        List<ChatMessage> messages = chatMessageRepository.findAllBySessionIdInTurnOrder(sessionId);
        return buildMaskedSource(session, messages).markdown();
    }

    /** 선택한 문답을 새 문서로 저장하고 처리 큐에 등록한다. */
    @Transactional
    public ChatWikiExportResponse export(String workspaceId, String userId, String sessionId,
                                         ChatWikiExportRequest request) {
        validate(request);
        ChatSession session = chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        List<ChatMessage> messages = chatMessageRepository.findAllBySessionIdInTurnOrder(sessionId);
        List<ChatMessage> selected = selectMessages(messages, request);

        ChatWikiSource source = buildMaskedSource(session, selected);
        if (source.isEmpty()) { // 완전한 문답이 하나도 없음
            throw new EmptyChatWikiExportException(sessionId);
        }

        String contentHash = stableContentHash(session, selected);
        String filename = titleOf(session) + ".md";

        log.info("[chat-wiki][export] session={} 전송 메시지={}건", sessionId, selected.size());

        DocumentService.ExportDocumentResult result = documentService.createChatExportDocument(
                workspaceId, userId, filename, source.markdown(), contentHash, pipelineBlocks(source));

        String status = result.skipped() ? "skipped" : "processing";
        log.info("[chat-wiki][export] 등록 session={} document={} status={}", sessionId, result.documentId(), status);
        return new ChatWikiExportResponse(result.documentId(), status);
    }

    private void validate(ChatWikiExportRequest request) {
        if (request == null || request.pairIds() == null || request.pairIds().isEmpty()) {
            throw new InvalidChatWikiExportRequestException("pair_ids는 필수입니다.");
        }
    }

    /** 선택된 pair_id의 메시지만 반환한다. */
    private List<ChatMessage> selectMessages(List<ChatMessage> messages, ChatWikiExportRequest request) {
        Set<String> selectedPairIds = new HashSet<>(request.pairIds());
        return messages.stream().filter(m -> selectedPairIds.contains(m.getPairId())).toList();
    }

    /** 본문과 블록 텍스트 모두 같은 규칙으로 마스킹한다. 블록이 파이프라인 입력이므로 여기서 새는 값이 없어야 한다. */
    private ChatWikiSource buildMaskedSource(ChatSession session, List<ChatMessage> messages) {
        ChatWikiSource source = serializer.serialize(session, messages);
        return new ChatWikiSource(
                secretMasker.mask(source.markdown()),
                source.blocks().stream()
                        .map(block -> new ChatSourceBlock(block.blockId(), secretMasker.mask(block.text())))
                        .toList());
    }

    private List<DocumentService.PipelineSourceBlock> pipelineBlocks(ChatWikiSource source) {
        return source.blocks().stream()
                .map(block -> new DocumentService.PipelineSourceBlock(block.blockId(), block.text()))
                .toList();
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

    /**
     * 문서 이름은 세션 제목을 쓴다. 신규 세션은 {@link ChatSession}이 기본 제목을 채우므로 비지 않고,
     * 제목 없이 저장된 예전 세션만 이 기본값으로 떨어진다. 세션 ID는 사용자에게 보이는 이름에 넣지 않는다.
     */
    private String titleOf(ChatSession session) {
        return (session.getTitle() != null && !session.getTitle().isBlank())
                ? session.getTitle()
                : ChatSession.DEFAULT_TITLE;
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
