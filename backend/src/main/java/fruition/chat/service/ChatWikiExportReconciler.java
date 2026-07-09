package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.repository.ChatMessageRepository;
import fruition.chat.repository.ChatSessionRepository;
import fruition.document.domain.Document;
import fruition.document.domain.DocumentStatus;
import fruition.document.domain.SourceBlock;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceBlockRepository;
import fruition.wiki.domain.DocumentWikiRelationType;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 Wiki page화 export의 처리 완료를 폴링으로 감지해 후처리한다.
 *
 * 파이프라인은 완료 시 documents.status='completed'와 wiki_pages/links/source_blocks를 한 트랜잭션으로 DB에 직접
 * 쓴다(백엔드 콜백 미경유). 그래서 완료를 push로 받을 수 없어, 완료된 chat_export 문서를 주기적으로 훑어
 * <b>full</b> export에 한해 (1) source wiki page를 세션에 연결하고 (2) 편입된 문답을 {@code chat_messages.wiki_page_id}로
 * 마킹한다(= 다음 full에서 필터로 제외). partial은 독립 page라 대상이 아니다. 모두 멱등하게 동작한다.
 *
 * session_id와 pair_id는 source_block 텍스트의 {@code [session_id:pair_id]} prefix에서 파싱한다(별도 저장 없음).
 *
 * NOTE: 현재 파이프라인은 append 미구현이라 full 재-export마다 새 page가 생긴다(source page 누적은 파이프라인 후속작업).
 */
@Component
public class ChatWikiExportReconciler {

    private static final Logger log = LoggerFactory.getLogger(ChatWikiExportReconciler.class);

    /** source_block 텍스트의 {@code [session_id:pair_id]} prefix. group(1)=session_id, group(2)=pair_id. */
    private static final Pattern PAIR_REF = Pattern.compile("\\[([^:\\]]+):([^\\]]+)\\]");

    private final DocumentRepository documentRepository;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final SourceBlockRepository sourceBlockRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    public ChatWikiExportReconciler(DocumentRepository documentRepository,
                                    DocumentWikiLinkRepository documentWikiLinkRepository,
                                    SourceBlockRepository sourceBlockRepository,
                                    ChatSessionRepository chatSessionRepository,
                                    ChatMessageRepository chatMessageRepository) {
        this.documentRepository = documentRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.sourceBlockRepository = sourceBlockRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void reconcile() {
        for (Document document : documentRepository.findAllByOriginAndStatus("chat_export", DocumentStatus.completed)) {
            if (!"full".equals(document.getSelectionMode())) {
                continue; // partial은 독립 page → 세션 연결·마킹 대상 아님
            }

            // source_blocks의 [session_id:pair_id]에서 세션과 문답을 파싱
            String sessionId = null;
            Set<String> pairIds = new LinkedHashSet<>();
            for (SourceBlock block : sourceBlockRepository.findAllByIdDocumentIdOrderByIdBlockIdAsc(document.getId())) {
                Matcher matcher = PAIR_REF.matcher(block.getText());
                if (matcher.find()) {
                    if (sessionId == null) {
                        sessionId = matcher.group(1);
                    }
                    pairIds.add(matcher.group(2));
                }
            }
            if (sessionId == null) {
                continue; // 아직 source_blocks 없거나 prefix 없음
            }

            String wikiPageId = resolveSourcePageId(document.getId());
            if (wikiPageId == null) {
                continue;
            }
            boolean linked = linkSession(sessionId, wikiPageId);
            int marked = markIngestedPairs(sessionId, pairIds, wikiPageId);
            if (linked || marked > 0) {
                log.info("[chat-wiki][reconcile] session={} page={} 신규연결={} 편입마킹={}건 (document={})",
                        sessionId, wikiPageId, linked, marked, document.getId());
            }
        }
    }

    private String resolveSourcePageId(String documentId) {
        return documentWikiLinkRepository
                .findAllByIdDocumentIdAndIdRelationType(documentId, DocumentWikiRelationType.source_of)
                .stream()
                .findFirst()
                .map(link -> link.getWikiPageId())
                .orElse(null);
    }

    /** 세션을 source page에 연결한다. 새로 연결(변경)했으면 true. */
    private boolean linkSession(String sessionId, String wikiPageId) {
        return chatSessionRepository.findById(sessionId).map(session -> {
            if (wikiPageId.equals(session.getWikiPageId())) {
                return false;
            }
            session.linkWikiPage(wikiPageId);
            chatSessionRepository.save(session);
            return true;
        }).orElse(false);
    }

    /** 편입된 문답을 세션 위키에 편입됨으로 마킹한다(멱등: 아직 null인 것만). 새로 마킹한 메시지 수를 반환. */
    private int markIngestedPairs(String sessionId, Set<String> pairIds, String wikiPageId) {
        if (pairIds.isEmpty()) {
            return 0;
        }
        List<ChatMessage> messages = chatMessageRepository.findAllBySession_IdAndPairIdIn(sessionId, pairIds);
        int marked = 0;
        for (ChatMessage message : messages) {
            if (message.getWikiPageId() == null) {
                message.markIngested(wikiPageId);
                marked++;
            }
        }
        chatMessageRepository.saveAll(messages);
        return marked;
    }
}
