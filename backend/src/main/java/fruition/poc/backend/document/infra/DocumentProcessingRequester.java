package fruition.poc.backend.document.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DocumentProcessingRequester {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingRequester.class);

    // 스텁: 텍스트 추출 및 Wiki 생성 파이프라인 연동 전
    public void request(String documentId) {
        log.info("[처리 요청 스텁] documentId={} 처리 파이프라인 연동 예정", documentId);
    }
}
