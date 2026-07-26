package fruition.wiki.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * lint 프록시의 공개 요청. LLM provider·비밀 설정은 노출하지 않고 pipeline 기본값을 쓴다.
 * dry_run=true는 proposal 조회, false는 promotion/relation materialization 실행이다.
 */
public record WikiMaintenanceLintRequest(
        @JsonProperty("dry_run") Boolean dryRun,
        @JsonProperty("materialize_promotions") Boolean materializePromotions
) {}
