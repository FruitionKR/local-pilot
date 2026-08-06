package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailVerificationResponse(
        @JsonProperty("verification_id") String verificationId,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("retry_after") long retryAfter
) {}
