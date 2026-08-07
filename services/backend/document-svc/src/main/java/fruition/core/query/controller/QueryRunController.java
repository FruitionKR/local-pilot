package fruition.core.query.controller;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.aihistory.exception.InvalidCallbackTokenException;
import fruition.core.query.domain.QueryRun;
import fruition.core.query.dto.PipelineEventCallbackRequest;
import fruition.core.query.dto.QueryRunStatusResponse;
import fruition.core.query.exception.QueryRunNotFoundException;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/query/runs")
public class QueryRunController {

    private static final Logger log = LoggerFactory.getLogger(QueryRunController.class);

    private final QueryRunStore queryRunStore;
    private final QueryEventBroker queryEventBroker;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final String internalToken;

    public QueryRunController(QueryRunStore queryRunStore,
                               QueryEventBroker queryEventBroker,
                               WorkspaceAccessGuard workspaceAccessGuard,
                               @Value("${app.internal.callback-token}") String internalToken) {
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.internalToken = internalToken;
    }

    @GetMapping("/{requestId}/events")
    public SseEmitter subscribe(@PathVariable String requestId,
                                @AuthenticationPrincipal String userId) {
        requireOwnedRun(requestId, userId);
        log.info("[질의 SSE 구독] requestId={}", requestId);
        return queryEventBroker.subscribe(requestId);
    }

    @PostMapping("/{requestId}/events/callback")
    public ResponseEntity<Void> receiveCallback(@PathVariable String requestId,
                                                 @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                 @RequestBody PipelineEventCallbackRequest body) {
        verifyInternalToken(token);
        requireRun(requestId);
        log.info("[질의 파이프라인 이벤트 callback 수신] requestId={} stage={} message={} dataKeys={}",
                requestId,
                body.stage(),
                body.message(),
                body.data() != null ? body.data().keySet() : java.util.List.of());
        queryEventBroker.publish(requestId, body.stage(), body.message(), body.data());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<QueryRunStatusResponse> getRun(@PathVariable String requestId,
                                                         @AuthenticationPrincipal String userId) {
        QueryRun run = requireOwnedRun(requestId, userId);
        log.info("[질의 run 상태 조회] requestId={} status={}", requestId, run.status());
        return ResponseEntity.ok(QueryRunStatusResponse.from(run));
    }

    private QueryRun requireRun(String requestId) {
        return queryRunStore.find(requestId)
                .orElseThrow(() -> new QueryRunNotFoundException(requestId));
    }

    /** run이 속한 워크스페이스의 멤버만 조회·구독할 수 있다. */
    private QueryRun requireOwnedRun(String requestId, String userId) {
        QueryRun run = requireRun(requestId);
        workspaceAccessGuard.requireMember(run.workspaceId(), userId);
        return run;
    }

    /** 길이가 달라도 시간차가 새지 않도록 상수 시간 비교를 쓴다. */
    private void verifyInternalToken(String token) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackTokenException();
        }
    }
}
