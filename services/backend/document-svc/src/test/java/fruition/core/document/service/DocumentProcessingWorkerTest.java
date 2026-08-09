package fruition.core.document.service;

import fruition.core.document.domain.DocumentProcessingQueue;
import fruition.core.document.repository.DocumentProcessingQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingWorkerTest {

    @Mock DocumentProcessingQueueRepository repository;
    @Mock DocumentService documentService;
    @Mock TransactionTemplate transactionTemplate;

    @Test
    void processNext_preparationFailure_returnsQueueToPending() {
        DocumentProcessingQueue item = new DocumentProcessingQueue("doc-1");
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        when(repository.findFirstByStatusOrderByCreatedAtAsc("pending")).thenReturn(Optional.of(item));
        when(repository.findAllByStatus("processing")).thenReturn(List.of(item));
        doThrow(new RuntimeException("database down")).when(documentService).doRequestProcessing("doc-1");

        new DocumentProcessingWorker(repository, documentService, transactionTemplate).processNext();

        assertThat(item.getStatus()).isEqualTo("pending");
        verify(repository, times(2)).save(item);
    }
}
