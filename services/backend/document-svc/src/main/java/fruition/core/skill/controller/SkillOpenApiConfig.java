package fruition.core.skill.controller;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
class SkillOpenApiConfig {

    private static final List<String> NULLABLE_STRING_FIELDS = List.of(
            "question", "skill_id", "version_id", "name", "description",
            "instructions_markdown", "skill_markdown");

    @Bean
    OpenApiCustomizer skillAuthoringResponseCustomizer() {
        return openApi -> {
            Schema<?> response = openApi.getComponents().getSchemas().get("SkillAuthoringResponse");
            if (response == null) return;
            NULLABLE_STRING_FIELDS.forEach(field -> response.getProperties().put(field, nullableString(null)));
            response.getProperties().put("scope_type", nullableString(List.of("personal", "team")));
        };
    }

    private static Schema<?> nullableString(List<String> allowableValues) {
        StringSchema value = new StringSchema();
        value.setEnum(allowableValues);
        Schema<Object> nullValue = new Schema<>();
        nullValue.setTypes(Set.of("null"));
        return new ComposedSchema().oneOf(List.of(value, nullValue));
    }
}
