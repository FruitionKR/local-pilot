package fruition.security.oauth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OAuthExchangeCodeStore {

    private static final long TTL_SECONDS = 60;

    private record Entry(String userId, Instant expiresAt) {}

    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    public String issue(String userId) {
        cleanupExpired();
        String code = generateCode();
        codes.put(code, new Entry(userId, Instant.now().plusSeconds(TTL_SECONDS)));
        return code;
    }

    public Optional<String> consume(String code) {
        Entry entry = codes.remove(code);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        codes.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private String generateCode() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
