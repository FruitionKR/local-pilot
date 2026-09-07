package fruition.access.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 사용자별 TOTP 설정. {@code activatedAt}이 null이면 등록만 하고 아직 켜지지 않은 상태다. */
@Entity
@Table(name = "user_mfa")
public class UserMfa {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "secret_cipher", nullable = false)
    private byte[] secretCipher;

    @Column(name = "secret_nonce", nullable = false)
    private byte[] secretNonce;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** 마지막으로 성공한 TOTP 시간 창. 같은 창의 코드를 두 번 쓰지 못하게 한다. */
    @Column(name = "last_used_counter")
    private Long lastUsedCounter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserMfa() {}

    public UserMfa(String userId, byte[] secretCipher, byte[] secretNonce) {
        this.userId = userId;
        this.secretCipher = secretCipher;
        this.secretNonce = secretNonce;
        this.createdAt = Instant.now();
    }

    /** 재등록. 켜기 전에 다시 시도하면 새 secret으로 갈아끼운다. */
    public void reissue(byte[] secretCipher, byte[] secretNonce) {
        this.secretCipher = secretCipher;
        this.secretNonce = secretNonce;
        this.activatedAt = null;
        this.lastUsedCounter = null;
    }

    public void activate(long counter) {
        this.activatedAt = Instant.now();
        this.lastUsedCounter = counter;
    }

    public void markCounterUsed(long counter) {
        this.lastUsedCounter = counter;
    }

    public boolean isActivated() {
        return activatedAt != null;
    }

    public boolean isCounterUsed(long counter) {
        return lastUsedCounter != null && counter <= lastUsedCounter;
    }

    public String getUserId() { return userId; }
    public byte[] getSecretCipher() { return secretCipher; }
    public byte[] getSecretNonce() { return secretNonce; }
    public Instant getActivatedAt() { return activatedAt; }
    public Long getLastUsedCounter() { return lastUsedCounter; }
}
