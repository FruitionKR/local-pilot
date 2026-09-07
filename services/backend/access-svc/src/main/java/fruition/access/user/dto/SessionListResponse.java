package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record SessionListResponse(
        @Schema(description = "폐기되지 않은 로그인 세션 목록. 최근 로그인 순.")
        List<SessionResponse> sessions) {}
