package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record PipelineEventCallbackRequest(
        @JsonProperty("event_type") String eventType,
        String stage,
        String message,
        Map<String, Object> data,
        @JsonProperty("request_id") String requestId,
        // pipeline assigns its own sequence/timestamp per HttpQueryEventPublisher instance;
        // bound here for forward-compat but currently unused — QueryEventBroker assigns its
        // own run-wide sequence/received_at so query.completed/failed (which pipeline never
        // emits) stay in the same numbering as query.log events.
        Long sequence,
        String timestamp
) {}
