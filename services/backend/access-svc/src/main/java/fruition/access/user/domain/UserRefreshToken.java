package fruition.access.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_refresh_tokens")
public class UserRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 발급 요청의 User-Agent 원문. 세션 목록에서 기기를 구분하는 유일한 단서다. */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    protected UserRefreshToken() {}

    public UserRefreshToken(String userId, String tokenHash, Instant expiresAt) {
        this(userId, tokenHash, expiresAt, null);
    }

    public UserRefreshToken(String userId, String tokenHash, Instant expiresAt, String userAgent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.createdAt = Instant.now();
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public boolean isValid() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getUserAgent() { return userAgent; }
}
