package fruition.util;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fruition API")
                        .description("Fruition PoC 백엔드 API")
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("Fruition")
                                .email("wonbb3313@gmail.com")));
    }
}
