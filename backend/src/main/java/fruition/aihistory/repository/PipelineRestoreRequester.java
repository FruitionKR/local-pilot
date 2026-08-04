package fruition.aihistory.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.aihistory.domain.RestoreAction;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * llmPipeline에 조립 지시서를 보낸다.
 *
 * <p>Backend가 못 하는 것만 넘긴다. 남은 조각을 적용 순서대로 붙여 페이지를 다시 써 달라는 요청이며,
 * 삭제는 이미 끝난 사실을 알려 임베딩·링크를 정리하게 한다.
 *
 * <p>조각의 object key는 보내지 않는다. llmPipeline이 {@code wiki/{ws}/pages/{page}/ops/{op}.json}을
 * 같은 규칙으로 조립하고 조각 안의 식별자까지 대조한다.
 */
@Component
public class PipelineRestoreRequester {

    private static final Logger log = LoggerFactory.getLogger(PipelineRestoreRequester.class);

    private final RestClient restClient;
    private final String ingestEndpoint;
    private final String lintEndpoint;

    public PipelineRestoreRequester(
            @Value("${app.wiki-restore.ingest-endpoint}") String ingestEndpoint,
            @Value("${app.wiki-restore.lint-endpoint}") String lintEndpoint,
            @Value("${app.wiki-restore.timeout-seconds:60}") int timeoutSeconds) {
        this.ingestEndpoint = ingestEndpoint;
        this.lintEndpoint = lintEndpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean sendIngestRestore(IngestRestoreRun run) {
        return post(ingestEndpoint, run, run.operationId());
    }

    public boolean sendLintRestore(LintRestoreRun run) {
        return post(lintEndpoint, run, run.operationId());
    }

    /**
     * @return 전송에 성공하면 {@code true}. 실패해도 예외를 던지지 않는다.
     *         복구는 DB에 이미 반영됐고 재작성만 보류되므로, 작업을 {@code notify_pending}으로 두고
     *         나중에 다시 보낸다.
     */
    private boolean post(String endpoint, Object body, String operationId) {
        try {
            restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("[복구 조립 지시서 전송 실패] endpoint={} operationId={} error={}",
                    endpoint, operationId, e.getMessage());
            return false;
        }
    }

    /**
     * ingest 되돌리기 지시서.
     *
     * @param restoreToOperationId source page를 어느 작업 시점으로 되돌릴지. 남는 기여가 없으면 null이고,
     *                             그때 llmPipeline이 source page를 삭제 대상으로 다룬다
     * @param cancelOperationIds   취소할 작업들. 비어 있으면 llmPipeline이 400으로 거절한다
     * @param sourcePage           원문 문서를 대표하는 페이지. ingest는 항상 하나를 건드린다
     */
    public record IngestRestoreRun(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("result_callback_url") String resultCallbackUrl,
            @JsonProperty("restore_to_operation_id") String restoreToOperationId,
            @JsonProperty("cancel_operation_ids") List<String> cancelOperationIds,
            @JsonProperty("source_page") SourcePage sourcePage,
            @JsonProperty("rebuild_pages") List<RebuildPage> rebuildPages,
            @JsonProperty("deleted_pages") List<String> deletedPages
    ) {
        public record SourcePage(@JsonProperty("page_id") String pageId) {}
    }

    /**
     * lint 되돌리기 지시서.
     *
     * @param targetOperationId 되돌릴 lint 작업. lint는 원문 문서가 없어 범위를 만들지 않는다
     */
    public record LintRestoreRun(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("result_callback_url") String resultCallbackUrl,
            @JsonProperty("target_operation_id") String targetOperationId,
            @JsonProperty("rebuild_pages") List<RebuildPage> rebuildPages,
            @JsonProperty("deleted_pages") List<String> deletedPages
    ) {}

    /** @param keepContributions 조립할 조각을 적용 순서대로. 순서가 결과를 바꾼다 */
    public record RebuildPage(
            @JsonProperty("page_id") String pageId,
            @JsonProperty("keep_contributions") List<Kept> keepContributions
    ) {
        public static RebuildPage from(PageRestorePlan page) {
            return new RebuildPage(page.pageId(), page.keepContributions().stream()
                    .map(k -> new Kept(k.operationId(), k.documentId()))
                    .toList());
        }
    }

    public record Kept(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("document_id") String documentId
    ) {}

    public static List<RebuildPage> rebuildPages(RestorePlan plan) {
        return plan.byAction(RestoreAction.rebuild).stream()
                .map(RebuildPage::from)
                .toList();
    }

    /** source page는 별도 필드로 넘기므로 삭제 목록에서 뺀다. llmPipeline이 스스로 추가한다. */
    public static List<String> deletedPages(RestorePlan plan, String sourcePageId) {
        return plan.byAction(RestoreAction.delete).stream()
                .map(PageRestorePlan::pageId)
                .filter(pageId -> !pageId.equals(sourcePageId))
                .toList();
    }
}
