package fruition.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerificationConfirmResponse(
        @JsonProperty("verification_token") String verificationToken,
        @JsonProperty("expires_in") long expiresIn
) {}
