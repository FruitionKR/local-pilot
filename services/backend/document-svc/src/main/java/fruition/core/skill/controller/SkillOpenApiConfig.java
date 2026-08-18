package fruition.core.skill.controller;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
class SkillOpenApiConfig {

    private static final List<String> NULLABLE_STRING_FIELDS = List.of(
            "question", "skill_id", "version_id", "scope_type", "name", "description",
            "instructions_markdown", "skill_markdown");

    @Bean
    OpenApiCustomizer skillAuthoringResponseCustomizer() {
        return openApi -> {
            Schema<?> response = openApi.getComponents().getSchemas().get("SkillAuthoringResponse");
            if (response == null) return;
            NULLABLE_STRING_FIELDS.forEach(field -> {
                Schema<?> value = (Schema<?>) response.getProperties().get(field);
                value.setNullable(null);
                response.getProperties().put(field, nullableString(value));
            });
        };
    }

    private static Schema<?> nullableString(Schema<?> value) {
        Schema<Object> nullValue = new Schema<>();
        nullValue.setTypes(Set.of("null"));
        return new ComposedSchema().oneOf(List.of(value, nullValue));
    }
}
