package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.domain.SourceBlock;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.repository.SourceBlockRepository;
import fruition.core.wiki.domain.DocumentWikiRelationType;
import fruition.core.wiki.repository.DocumentWikiLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 Wiki page화 export의 처리 완료를 폴링으로 감지해 후처리한다.
 *
 * 파이프라인은 완료 시 documents.status='completed'와 wiki_pages/links/source_blocks를 한 트랜잭션으로 DB에 직접
 * 쓴다(백엔드 콜백 미경유). 그래서 완료를 push로 받을 수 없어, 완료된 chat_export 문서를 주기적으로 훑어 후처리한다.
 * <ul>
 *   <li><b>full</b>: (1) source wiki page를 세션에 연결하고 (2) 편입된 문답을 {@code chat_messages.wiki_page_id}로
 *       마킹한다(= 다음 full에서 필터로 제외).</li>
 *   <li><b>partial</b>: 발췌 문답 ↔ 위키 페이지 멤버십을 {@code chat_partial_wiki}에 기록한다(1:N). 세션 연결·마킹은
 *       하지 않는다(발췌는 독립 page이며, full 제외 필터를 오염시키면 안 되기 때문).</li>
 * </ul>
 * 모두 멱등하게 동작한다.
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
    private final ChatPartialWikiRepository chatPartialWikiRepository;

    public ChatWikiExportReconciler(DocumentRepository documentRepository,
                                    DocumentWikiLinkRepository documentWikiLinkRepository,
                                    SourceBlockRepository sourceBlockRepository,
                                    ChatSessionRepository chatSessionRepository,
                                    ChatMessageRepository chatMessageRepository,
                                    ChatPartialWikiRepository chatPartialWikiRepository) {
        this.documentRepository = documentRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.sourceBlockRepository = sourceBlockRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatPartialWikiRepository = chatPartialWikiRepository;
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void reconcile() {
        for (Document document : documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull("chat_export", DocumentStatus.completed)) {
            String mode = document.getSelectionMode();

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

            if ("full".equals(mode)) {
                boolean linked = linkSession(sessionId, wikiPageId);
                int marked = markIngestedPairs(sessionId, pairIds, wikiPageId);
                if (linked || marked > 0) {
                    log.info("[chat-wiki][reconcile] full session={} page={} 신규연결={} 편입마킹={}건 (document={})",
                            sessionId, wikiPageId, linked, marked, document.getId());
                }
            } else if ("partial".equals(mode)) {
                int added = recordPartialPairs(document.getWorkspaceId(), sessionId, pairIds, wikiPageId, document.getId());
                if (added > 0) {
                    log.info("[chat-wiki][reconcile] partial session={} page={} 발췌기록={}건 (document={})",
                            sessionId, wikiPageId, added, document.getId());
                }
            }

            // 후처리 완료 → reconciled_at 세팅(다음 tick 조회에서 제외). source_blocks/page 미생성이면 위에서 continue돼 여기 도달 안 함.
            document.markReconciled(Instant.now());
            documentRepository.save(document);
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

    /** 세션을 source page에 연결한다. 아직 미연결인 세션만 연결한다(멱등, 다중 full 문서에도 무한 재기록/flip 방지). 새로 연결했으면 true. */
    private boolean linkSession(String sessionId, String wikiPageId) {
        return chatSessionRepository.findById(sessionId).map(session -> {
            if (session.getWikiPageId() != null) {
                return false; // 이미 연결됨 → 재연결하지 않음
            }
            session.linkWikiPage(wikiPageId);
            chatSessionRepository.save(session);
            return true;
        }).orElse(false);
    }

    /** partial 발췌 문답을 chat_partial_wiki에 기록한다(멱등: 이 문서로 이미 기록했으면 skip). 새로 기록한 행 수를 반환. */
    private int recordPartialPairs(String workspaceId, String sessionId, Set<String> pairIds, String wikiPageId, String documentId) {
        if (pairIds.isEmpty() || chatPartialWikiRepository.existsByDocumentId(documentId)) {
            return 0;
        }
        Instant now = Instant.now();
        int added = 0;
        for (String pairId : pairIds) {
            String id = "cpw_" + UUID.randomUUID().toString().replace("-", "");
            chatPartialWikiRepository.save(new ChatPartialWiki(id, workspaceId, sessionId, pairId, wikiPageId, documentId, now));
            added++;
        }
        return added;
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
