package fruition.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentTurnResponse(
        String documentId,
        long baseVersion,
        String requestId,
        JsonNode result
) {}
