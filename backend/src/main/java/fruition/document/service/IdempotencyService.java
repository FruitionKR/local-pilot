package fruition.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.document.domain.IdempotencyRecord;
import fruition.document.exception.IdempotencyConflictException;
import fruition.document.exception.InvalidIdempotencyKeyException;
import fruition.document.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** 여러 endpoint에서 공유하는 Idempotency-Key 저장·재생 도우미. */
@Service
public class IdempotencyService {

    private static final long TTL_SECONDS = 24L * 60 * 60;

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key는 1자 이상 255자 이하여야 합니다.");
        }
    }

    public String requestHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\0", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public <T> Optional<T> replay(String userId, String endpointScope, String idempotencyKey,
                                  String requestHash, Class<T> responseType) {
        Optional<IdempotencyRecord> found =
                repository.findByUserIdAndEndpointScopeAndIdempotencyKey(userId, endpointScope, idempotencyKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = found.get();
        if (!record.getExpiresAt().isAfter(Instant.now())) {
            repository.delete(record);
            repository.flush();
            return Optional.empty();
        }
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.");
        }
        try {
            return Optional.of(objectMapper.readValue(record.getResponseBody(), responseType));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답을 복원할 수 없습니다.", e);
        }
    }

    public void save(String userId, String endpointScope, String idempotencyKey, String requestHash,
                     int responseStatus, String resourceId, Object response) {
        String responseBody;
        try {
            responseBody = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답을 저장할 수 없습니다.", e);
        }
        Instant now = Instant.now();
        repository.save(new IdempotencyRecord(
                UUID.randomUUID(), userId, endpointScope, idempotencyKey, requestHash,
                responseStatus, resourceId, responseBody, now, now.plusSeconds(TTL_SECONDS)));
    }
}
