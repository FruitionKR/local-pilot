package fruition.access.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 기기를 잃었을 때 쓰는 1회용 코드. 원문은 발급 시 한 번만 노출하고 해시만 남긴다. */
@Entity
@Table(name = "user_mfa_recovery_codes")
public class UserMfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserMfaRecoveryCode() {}

    public UserMfaRecoveryCode(String userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = Instant.now();
    }

    public void consume() {
        this.consumedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public Instant getConsumedAt() { return consumedAt; }
}
