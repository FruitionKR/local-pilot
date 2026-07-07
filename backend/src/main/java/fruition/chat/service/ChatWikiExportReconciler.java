package fruition.chat.service;

import fruition.chat.domain.ChatSession;
import fruition.chat.repository.ChatSessionRepository;
import fruition.document.domain.Document;
import fruition.document.domain.DocumentStatus;
import fruition.document.repository.DocumentRepository;
import fruition.wiki.domain.DocumentWikiRelationType;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅 Wiki page화 export 문서의 처리 완료를 폴링으로 감지해, 파이프라인이 만든 source wiki page를 원본 세션에 연결한다.
 *
 * 파이프라인은 완료 시 documents.status='completed'와 wiki_pages/links를 한 트랜잭션으로 DB에 직접 쓴다
 * (백엔드 콜백을 거치지 않음). 따라서 완료를 push로 받을 수 없어, 아직 연결되지 않은 export를 주기적으로 찾아 연결한다.
 * 멱등하게 동작한다.
 */
@Component
public class ChatWikiExportReconciler {

    private final ChatSessionRepository chatSessionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;

    public ChatWikiExportReconciler(ChatSessionRepository chatSessionRepository,
                                    DocumentRepository documentRepository,
                                    DocumentWikiLinkRepository documentWikiLinkRepository) {
        this.chatSessionRepository = chatSessionRepository;
        this.documentRepository = documentRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void reconcile() {
        for (ChatSession session : chatSessionRepository.findByWikiExportDocumentIdIsNotNullAndWikiPageIdIsNull()) {
            String documentId = session.getWikiExportDocumentId();
            Document document = documentRepository.findById(documentId).orElse(null);
            if (document == null || document.getStatus() != DocumentStatus.completed) {
                continue; // 아직 처리 중이거나 실패 → 다음 tick에 재확인
            }
            linkSourceWikiPage(session, documentId);
        }
    }

    private void linkSourceWikiPage(ChatSession session, String documentId) {
        documentWikiLinkRepository
                .findAllByIdDocumentIdAndIdRelationType(documentId, DocumentWikiRelationType.source_of)
                .stream()
                .findFirst()
                .ifPresent(link -> {
                    session.linkWikiPage(link.getWikiPageId());
                    chatSessionRepository.save(session);
                });
    }
}
