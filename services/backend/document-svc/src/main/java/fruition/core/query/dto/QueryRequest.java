package fruition.core.query.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;

public record QueryRequest(
        @NotBlank(message = "질문은 비어 있을 수 없습니다.")
        String question,
        String provider,
        String model,
        @JsonProperty("allow_web_search")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @NotNull(message = "웹 검색 사용 여부는 필수입니다.")
        Boolean allowWebSearch
) {
    public static final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return switch (parser.currentToken()) {
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                default -> (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            };
        }

        @Override
        public Boolean getNullValue(DeserializationContext context) throws JsonMappingException {
            if (context.getParser().currentToken() == JsonToken.VALUE_NULL) {
                return context.reportInputMismatch(Boolean.class, "웹 검색 사용 여부는 true 또는 false여야 합니다.");
            }
            return null;
        }
    }
}
