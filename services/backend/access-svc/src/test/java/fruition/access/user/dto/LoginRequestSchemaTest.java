package fruition.access.user.dto;

import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenApiConfigTest에서 분리: LoginRequest는 access-svc 소유 DTO라 스키마 검증도 여기서 한다.
 * example·defaultValue는 Swagger try-it-out 폼에 그대로 채워지는 값이라, 다른 비밀번호 필드
 * (SignupRequest·PasswordResetRequest)와 같은 값을 쓴다.
 */
class LoginRequestSchemaTest {

    @Test
    void loginRequestSchema_hasSwaggerDefaults() {
        var schema = ModelConverters.getInstance()
                .read(LoginRequest.class)
                .get("LoginRequest");
        var email = (io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("email");
        var password = (io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("password");

        assertThat(email.getDefault()).isEqualTo("user@example.com");
        assertThat(email.getExample()).isEqualTo("user@example.com");
        assertThat(password.getDefault()).isEqualTo("password1234");
        assertThat(password.getExample()).isEqualTo("password1234");
    }
}
