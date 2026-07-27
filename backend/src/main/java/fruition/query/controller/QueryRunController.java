package fruition.query.controller;

import fruition.query.domain.QueryRun;
import fruition.query.dto.PipelineEventCallbackRequest;
import fruition.query.dto.QueryRunStatusResponse;
import fruition.query.exception.QueryRunNotFoundException;
import fruition.query.service.QueryEventBroker;
import fruition.query.service.QueryRunStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/query/runs")
@Tag(name = "Query Runs", description = "비동기 질의 진행 SSE 구독 및 상태 조회 API")
public class QueryRunController {

    private static final Logger log = LoggerFactory.getLogger(QueryRunController.class);

    private final QueryRunStore queryRunStore;
    private final QueryEventBroker queryEventBroker;

    public QueryRunController(QueryRunStore queryRunStore,
                               QueryEventBroker queryEventBroker) {
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
    }

    @Operation(summary = "질의 진행 SSE 구독", description = "비동기 질의의 진행 이벤트를 SSE로 실시간 수신합니다.")
    @GetMapping("/{requestId}/events")
    public SseEmitter subscribe(@PathVariable String requestId) {
        requireRun(requestId);
        log.info("[질의 SSE 구독] requestId={}", requestId);
        return queryEventBroker.subscribe(requestId);
    }

    @Operation(summary = "질의 파이프라인 이벤트 콜백(내부)", description = "질의 파이프라인이 진행 이벤트를 백엔드로 전달하는 내부 콜백입니다.")
    @PostMapping("/{requestId}/events/callback")
    public ResponseEntity<Void> receiveCallback(@PathVariable String requestId,
                                                 @RequestBody PipelineEventCallbackRequest body) {
        requireRun(requestId);
        log.info("[질의 파이프라인 이벤트 callback 수신] requestId={} stage={} message={} dataKeys={}",
                requestId,
                body.stage(),
                body.message(),
                body.data() != null ? body.data().keySet() : java.util.List.of());
        queryEventBroker.publish(requestId, body.stage(), body.message(), body.data());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "질의 run 상태 조회", description = "비동기 질의 run의 현재 상태와 결과를 조회합니다.")
    @GetMapping("/{requestId}")
    public ResponseEntity<QueryRunStatusResponse> getRun(@PathVariable String requestId) {
        QueryRun run = requireRun(requestId);
        log.info("[질의 run 상태 조회] requestId={} status={}", requestId, run.status());
        return ResponseEntity.ok(QueryRunStatusResponse.from(run));
    }

    private QueryRun requireRun(String requestId) {
        return queryRunStore.find(requestId)
                .orElseThrow(() -> new QueryRunNotFoundException(requestId));
    }
}
