package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record QueryRequest(
        @NotBlank(message = "질문은 비어 있을 수 없습니다.")
        String question,
        String provider,
        String model,
        @JsonProperty("allow_web_search")
        @NotNull(message = "웹 검색 사용 여부는 필수입니다.")
        Boolean allowWebSearch
) {}
