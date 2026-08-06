package fruition.core.aihistory.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 편집안에 붙는 일회용 적용 표. Agent turn에서 발급하고 저장 요청에서 소비한다.
 *
 * <p>{@code source=agent} 문자열은 클라이언트가 임의로 넣을 수 있어 수동 편집을 AI 작업으로
 * 위장할 수 있다. Backend가 발급한 값을 대조해야 로그가 오염되지 않는다.
 *
 * <p>DB에 남기지 않는 이유는 <b>적용하지 않은 편집안을 기록하지 않기</b> 위해서다. 사용자가
 * 편집안을 버리거나 창을 닫으면 표는 만료로 사라지고 아무 흔적도 남지 않는다. 서버가 재시작되면
 * 발급된 표가 무효가 되는데, 그때는 사용자가 편집을 다시 요청하면 된다.
 */
@Component
public class AgentApplyOperationStore {

    /** 편집안을 검토하고 적용하기까지 걸리는 시간. 넉넉히 30분을 준다. */
    private static final long TTL_SECONDS = 30 * 60;

    private record Entry(String userId, String documentId, Instant expiresAt) {}

    private final Map<String, Entry> issued = new ConcurrentHashMap<>();

    /** 편집안 하나에 대한 적용 표를 발급한다. */
    public String issue(String userId, String documentId) {
        cleanupExpired();
        String operationId = "op_" + randomSuffix();
        issued.put(operationId, new Entry(userId, documentId, Instant.now().plusSeconds(TTL_SECONDS)));
        return operationId;
    }

    /**
     * 표를 확인하고 소비한다. 같은 표로 두 번 기록되지 않도록 조회와 동시에 제거한다.
     *
     * @return 이 사용자·문서에 발급된 유효한 표면 {@code true}
     */
    public boolean consume(String operationId, String userId, String documentId) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        Entry entry = issued.remove(operationId);
        return entry != null
                && !entry.expiresAt().isBefore(Instant.now())
                && entry.userId().equals(userId)
                && entry.documentId().equals(documentId);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        issued.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
