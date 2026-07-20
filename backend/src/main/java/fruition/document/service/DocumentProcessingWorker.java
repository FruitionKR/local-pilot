package fruition.document.service;

import fruition.document.repository.DocumentProcessingQueueRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DocumentProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingWorker.class);

    private final DocumentProcessingQueueRepository queueRepository;
    private final DocumentService documentService;
    private final TransactionTemplate transactionTemplate;

    public DocumentProcessingWorker(DocumentProcessingQueueRepository queueRepository,
                                    DocumentService documentService,
                                    TransactionTemplate transactionTemplate) {
        this.queueRepository = queueRepository;
        this.documentService = documentService;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void resetStuckItems() {
        transactionTemplate.execute(status -> {
            queueRepository.findAllByStatus("processing").forEach(item -> {
                log.warn("[처리 큐 리셋] document_id={} (서버 재시작으로 인한 stuck 복구)", item.getDocumentId());
                item.setStatus("pending");
                queueRepository.save(item);
            });
            return null;
        });
    }

    @Scheduled(fixedDelay = 2000)
    public void processNext() {
        String documentId = transactionTemplate.execute(status ->
                queueRepository.findFirstByStatusOrderByCreatedAtAsc("pending")
                        .map(item -> {
                            log.info("[문서 처리 큐 선택] documentId={} status=pending->processing", item.getDocumentId());
                            item.setStatus("processing");
                            queueRepository.save(item);
                            return item.getDocumentId();
                        })
                        .orElse(null)
        );

        if (documentId == null) return;

        try {
            documentService.doRequestProcessing(documentId);
        } finally {
            String finalDocumentId = documentId;
            transactionTemplate.execute(status -> {
                queueRepository.deleteByDocumentId(finalDocumentId);
                log.info("[문서 처리 큐 삭제] documentId={}", finalDocumentId);
                return null;
            });
        }
    }
}
