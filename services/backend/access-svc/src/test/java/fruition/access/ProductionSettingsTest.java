package fruition.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSettingsTest {
    private MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.mail.host", "smtp.example.com")
                .withProperty("spring.mail.port", "587")
                .withProperty("spring.mail.username", "test-user")
                .withProperty("spring.mail.password", "test-password")
                .withProperty("app.auth.email-verification.from", "noreply@example.com")
                .withProperty("app.auth.mfa.encryption-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .withProperty("app.workspace.invitation.accept-url", "https://app.example.com/invitations");
    }

    @Test
    void acceptsCompleteProductionSettings() {
        assertDoesNotThrow(() -> new ProductionSettings(valid()));
    }

    @Test
    void rejectsEachMissingRequiredSetting() {
        for (String key : new String[] {"spring.mail.host", "spring.mail.port", "spring.mail.username",
                "spring.mail.password", "app.auth.email-verification.from", "app.auth.mfa.encryption-key",
                "app.workspace.invitation.accept-url"}) {
            MockEnvironment environment = valid();
            environment.setProperty(key, "");
            assertThrows(RuntimeException.class, () -> new ProductionSettings(environment), key);
        }
    }

    @Test
    void rejectsInvalidKeyInvitationAndFixedCode() {
        for (String value : new String[] {"http://app.example.com/invitations", "https://localhost/invitations", "https://REPLACE_ME_APP_DOMAIN/invitations"}) {
            assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                    .withProperty("app.workspace.invitation.accept-url", value)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.auth.mfa.encryption-key", "dGVzdA==")));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.auth.email-verification.dev-fixed-code", "123456")));
    }
}
