package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatSession;
import fruition.chat.dto.ChatWikiExportResponse;
import fruition.chat.repository.ChatMessageRepository;
import fruition.document.service.DocumentService;
import fruition.util.SecretMasker;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 채팅 세션을 Wiki page화하기 위한 export 오케스트레이션.
 *
 * 세션을 Markdown으로 직렬화·마스킹한 뒤, 문서 저장/큐 등록은 {@link DocumentService}에 위임한다.
 * 실제 위키 생성은 기존 문서 ingestion 파이프라인이 담당한다. (docs/spec/features/chat-wiki-export.md)
 *
 * NOTE(현재 단계): 완료 후 세션↔위키 연결과 block 매핑(Stage 3~4)은 아직 없다.
 */
@Service
public class ChatWikiExportService {

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

    /** 세션을 Wiki page화용 Markdown으로 직렬화하고 비밀값을 마스킹해 반환한다. (저장/파이프라인 호출 없음) */
    public String previewMarkdown(String workspaceId, String userId, String sessionId) {
        ChatSession session = chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        List<ChatMessage> messages = chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(sessionId);
        return buildMaskedMarkdown(session, messages);
    }

    /** 세션을 문서로 저장하고 처리 큐에 등록한다. 파이프라인이 이후 비동기로 위키를 생성한다. */
    public ChatWikiExportResponse export(String workspaceId, String userId, String sessionId) {
        ChatSession session = chatSessionService.verifyOwnedSession(workspaceId, userId, sessionId);
        List<ChatMessage> messages = chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(sessionId);

        String markdown = buildMaskedMarkdown(session, messages);
        String contentHash = stableContentHash(session, messages);
        String filename = titleOf(session) + ".md";

        DocumentService.ExportDocumentResult result =
                documentService.createChatExportDocument(workspaceId, userId, filename, markdown, contentHash);

        String status = result.skipped() ? "skipped" : "processing";
        return new ChatWikiExportResponse(result.documentId(), status);
    }

    private String buildMaskedMarkdown(ChatSession session, List<ChatMessage> messages) {
        String markdown = serializer.serialize(session, messages, Instant.now());
        return secretMasker.mask(markdown);
    }

    /**
     * 재-export 중복 판별용 안정 해시. exported_at 같은 휘발성 값은 제외하고
     * conversation(session id)과 completed 메시지의 role/content만 기준으로 삼는다. (spec A-1)
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
