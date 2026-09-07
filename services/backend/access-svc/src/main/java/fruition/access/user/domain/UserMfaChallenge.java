package fruition.access.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 비밀번호는 통과했지만 아직 코드를 못 받은 중간 상태. 토큰 원문은 응답으로만 나가고 해시만 남는다. */
@Entity
@Table(name = "user_mfa_challenges")
public class UserMfaChallenge {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserMfaChallenge() {}

    public UserMfaChallenge(String id, String userId, String tokenHash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void consume() {
        this.consumedAt = Instant.now();
    }

    public boolean isUsable() {
        return consumedAt == null && expiresAt.isAfter(Instant.now());
    }

    public String getUserId() { return userId; }
}
