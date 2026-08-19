package fruition.core.chat.service;

import fruition.core.chat.domain.ChatPartialWiki;
import fruition.core.chat.repository.ChatPartialWikiRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.service.DocumentService;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 Wiki page화 export의 처리 완료를 폴링으로 감지해 후처리한다.
 *
 * Backend가 pipeline run 상태를 폴링해 완료 처리한 chat_export 문서를 주기적으로 훑고,
 * Wiki 현재 상태는 pipeline 내부 API로 읽어 후처리한다. 후처리는 발췌 문답 ↔ 위키 페이지 멤버십을
 * {@code chat_partial_wiki}에 기록하는 것뿐이다(1:N). export마다 독립 source page가 생기므로
 * 세션을 특정 page에 연결하지 않는다. 멱등하게 동작한다.
 *
 * session_id와 pair_id는 source_block block_id의 {@code session_id:pair_id} provenance에서 파싱한다(별도 저장 없음).
 */
@Component
public class ChatWikiExportReconciler {

    private static final Logger log = LoggerFactory.getLogger(ChatWikiExportReconciler.class);

    /** source_block block_id의 {@code session_id:pair_id} provenance. group(1)=session_id, group(2)=pair_id. */
    private static final Pattern PAIR_REF = Pattern.compile("(?U)^([^:\\s]+):([^:\\s]+)$");

    private final DocumentRepository documentRepository;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final ChatPartialWikiRepository chatPartialWikiRepository;
    private final DocumentService documentService;
    private final TransactionTemplate transactionTemplate;

    public ChatWikiExportReconciler(DocumentRepository documentRepository,
                                    PipelineWikiStateRequester pipelineWikiStateRequester,
                                    ChatPartialWikiRepository chatPartialWikiRepository,
                                    DocumentService documentService,
                                    TransactionTemplate transactionTemplate) {
        this.documentRepository = documentRepository;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.chatPartialWikiRepository = chatPartialWikiRepository;
        this.documentService = documentService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 문서 하나가 실패해도 나머지는 확정되도록 문서 단위로 처리한다. 한 트랜잭션으로 묶으면
     * 뒤쪽 문서의 실패가 앞서 끝낸 문서까지 되돌려, 문서 하나가 전체 후처리를 막는다.
     */
    @Scheduled(fixedDelay = 3000)
    public void reconcile() {
        for (Document document : documentRepository.findAllByOriginAndStatusAndReconciledAtIsNull(
                "chat_export", DocumentStatus.completed)) {
            try {
                reconcileOne(document);
            } catch (RuntimeException e) {
                // 이 문서만 건너뛴다. reconciled_at을 남기지 않았으므로 다음 tick에 다시 시도한다.
                // 예외를 마지막 인자로 넘겨 스택을 남긴다. DB 쓰기 실패까지 여기로 모이므로 메시지만으론 부족하다.
                log.warn("[chat-wiki][reconcile] 건너뜀 document={}", document.getId(), e);
            }
        }
    }

    /** 문서 하나의 후처리. Wiki 상태 조회는 네트워크 호출이라 트랜잭션 밖에서 먼저 끝낸다. */
    private void reconcileOne(Document document) {
        var wikiContext = pipelineWikiStateRequester.documentContext(
                document.getWorkspaceId(), document.getId());

        // source_blocks의 block_id에 보존된 session_id:pair_id provenance에서 세션과 문답을 파싱
        Set<String> sessionIds = new LinkedHashSet<>();
        Set<String> pairIds = new LinkedHashSet<>();
        for (var block : wikiContext.sourceBlocks()) {
            if (block.blockId() == null) {
                continue;
            }
            Matcher matcher = PAIR_REF.matcher(block.blockId());
            if (!matcher.matches()) {
                continue;
            }
            sessionIds.add(matcher.group(1));
            pairIds.add(matcher.group(2));
        }
        if (sessionIds.size() != 1) {
            return; // 유효 provenance가 없거나 여러 세션이 섞여 있으면 재시도
        }
        String sessionId = sessionIds.iterator().next();

        PipelineWikiStateRequester.DocumentPage sourcePage = wikiContext.pages().stream()
                .filter(page -> "source_of".equals(page.relationType()))
                .findFirst()
                .orElse(null);
        if (sourcePage == null) {
            return;
        }

        transactionTemplate.execute(status -> {
            // export 시점 이름은 첫 질문을 줄인 임시값이다. 내용을 요약한 페이지 제목이 나왔으면 그걸로 확정한다.
            documentService.confirmChatExportName(document, sourcePage.title());

            if (!recordPairs(document.getWorkspaceId(), sessionId, pairIds, sourcePage.id(), document.getId())) {
                return null; // 모든 pair 멤버십이 준비될 때까지 reconciled_at을 남기지 않고 재시도
            }
            log.info("[chat-wiki][reconcile] session={} page={} 발췌기록 완료 (document={})",
                    sessionId, sourcePage.id(), document.getId());

            // 후처리 완료 → reconciled_at 세팅(다음 tick 조회에서 제외).
            document.markReconciled(Instant.now());
            documentRepository.save(document);
            return null;
        });
    }

    /** 내보낸 문답을 pair별로 기록한다(멱등·부분 실패 후 재시도 가능). */
    private boolean recordPairs(String workspaceId, String sessionId, Set<String> pairIds, String wikiPageId, String documentId) {
        if (pairIds.isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        for (String pairId : pairIds) {
            if (chatPartialWikiRepository.existsByDocumentIdAndPairId(documentId, pairId)) {
                continue;
            }
            String id = "cpw_" + UUID.randomUUID().toString().replace("-", "");
            chatPartialWikiRepository.save(new ChatPartialWiki(id, workspaceId, sessionId, pairId, wikiPageId, documentId, now));
        }
        return true;
    }
}
