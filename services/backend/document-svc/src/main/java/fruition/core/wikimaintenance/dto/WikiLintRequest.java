package fruition.core.wikimaintenance.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

public record WikiLintRequest(
        @JsonProperty("materialize_promotions")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        Boolean materializePromotions,
        @JsonProperty("dry_run")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        Boolean dryRun
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
    }
}
