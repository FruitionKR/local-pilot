package fruition.access.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    /** 일반 회원가입으로 만든 계정의 provider 값. */
    public static final String PROVIDER_LOCAL = "local";

    @Id
    private String id;

    @Column(nullable = false)
    private String email;

    /** 계정을 만든 수단. 일반 회원가입은 "local", OAuth는 provider 등록 ID. */
    @Column(nullable = false)
    private String provider;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public User(String id, String email, String provider, String displayName, String passwordHash) {
        this.id = id;
        this.email = email;
        this.provider = provider;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getProvider() { return provider; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
