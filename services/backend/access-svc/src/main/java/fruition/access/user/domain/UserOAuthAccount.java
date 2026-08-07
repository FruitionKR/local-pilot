package fruition.access.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "user_oauth_accounts",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_user_oauth_accounts_provider_provider_user_id",
        columnNames = {"provider", "provider_user_id"}
    )
)
public class UserOAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserOAuthAccount() {}

    public UserOAuthAccount(String userId, String provider, String providerUserId) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getProviderUserId() { return providerUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
