package fruition.util;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SECURITY_SCHEME = "bearerAuth";
    private static final String WORKSPACE_ID_EXAMPLE = "ws_9d47a0e9a6324341b47562553b75f92a";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fruition API")
                        .description("Fruition PoC 백엔드 API")
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("Fruition")
                                .email("wonbb3313@gmail.com")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SECURITY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SECURITY_SCHEME, new SecurityScheme()
                                .name(BEARER_SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public OpenApiCustomizer workspaceIdExampleCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .filter(operation -> operation.getParameters() != null)
                    .flatMap(operation -> operation.getParameters().stream())
                    .filter(parameter -> "workspace_id".equals(parameter.getName()))
                    .forEach(parameter -> {
                        parameter.setExample(WORKSPACE_ID_EXAMPLE);
                        if (parameter.getSchema() != null) {
                            parameter.getSchema().setDefault(WORKSPACE_ID_EXAMPLE);
                        }
                    });
        };
    }
}
