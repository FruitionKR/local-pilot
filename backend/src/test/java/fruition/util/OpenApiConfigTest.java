package fruition.util;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import fruition.user.dto.LoginRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void workspaceIdExampleCustomizer_setsExampleAndDefault() {
        Parameter workspaceId = new Parameter()
                .name("workspace_id")
                .in("path")
                .schema(new StringSchema());
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(
                "/api/workspaces/{workspace_id}/documents",
                new PathItem().get(new Operation().addParametersItem(workspaceId))));

        new OpenApiConfig().workspaceIdExampleCustomizer().customise(openApi);

        assertThat(workspaceId.getExample()).isEqualTo("ws_9d47a0e9a6324341b47562553b75f92a");
        assertThat(workspaceId.getSchema().getDefault())
                .isEqualTo("ws_9d47a0e9a6324341b47562553b75f92a");
    }

    @Test
    void loginRequestSchema_hasSwaggerDefaults() {
        var schema = ModelConverters.getInstance()
                .read(LoginRequest.class)
                .get("LoginRequest");
        var email = (io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("email");
        var password = (io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("password");

        assertThat(email.getDefault()).isEqualTo("user@example.com");
        assertThat(email.getExample()).isEqualTo("user@example.com");
        assertThat(password.getDefault()).isEqualTo("stringst");
        assertThat(password.getExample()).isEqualTo("stringst");
    }
}
