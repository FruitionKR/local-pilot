package fruition.aihistory.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.domain.RestoreAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

/**
 * llmPipeline에 조립 지시서를 보낸다.
 *
 * <p>Backend가 못 하는 것만 넘긴다. 남은 조각을 적용 순서대로 붙여 페이지를 다시 써 달라는 요청이며,
 * 되돌리기와 삭제는 이미 끝난 사실을 알려 임베딩·링크를 정리하게 한다.
 */
@Component
public class PipelineRestoreRequester {

    private static final Logger log = LoggerFactory.getLogger(PipelineRestoreRequester.class);

    private final RestClient restClient;
    private final String endpoint;

    public PipelineRestoreRequester(
            @Value("${app.wiki-restore.endpoint}") String endpoint,
            @Value("${app.wiki-restore.timeout-seconds:60}") int timeoutSeconds) {
        this.endpoint = endpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * @return 전송에 성공하면 {@code true}. 실패해도 예외를 던지지 않는다.
     *         복구는 DB에 이미 반영됐고 재작성만 보류되므로, 작업을 {@code notify_pending}으로 두고
     *         나중에 다시 보낸다.
     */
    public boolean send(RestoreRun run) {
        try {
            restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(run)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("[복구 조립 지시서 전송 실패] operationId={} error={}",
                    run.operationId(), e.getMessage());
            return false;
        }
    }

    public record RestoreRun(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("restored_from") String restoredFrom,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("result_callback_url") String resultCallbackUrl,
            @JsonProperty("excluded_operations") Set<String> excludedOperations,
            @JsonProperty("rebuild_pages") List<RebuildPage> rebuildPages,
            @JsonProperty("restored_pages") List<RestoredPage> restoredPages,
            @JsonProperty("deleted_pages") List<String> deletedPages
    ) {

        /**
         * @param contributionCount 복구 후 남는 기여 수. Backend가 계산해 내려준다
         * @param keepContributions 조립할 조각을 적용 순서대로. 순서가 결과를 바꾼다
         */
        public record RebuildPage(
                @JsonProperty("page_id") String pageId,
                @JsonProperty("contribution_count") int contributionCount,
                @JsonProperty("keep_contributions") List<Kept> keepContributions
        ) {}

        public record Kept(
                @JsonProperty("operation_id") String operationId,
                @JsonProperty("document_id") String documentId,
                @JsonProperty("object_key") String objectKey
        ) {}

        /** 되돌리기가 끝난 페이지. 재작성 대상이 아니라 임베딩 갱신용 통보다. */
        public record RestoredPage(
                @JsonProperty("page_id") String pageId,
                long revision
        ) {}

        public static RestoreRun from(String operationId, String restoredFrom, String workspaceId,
                                      String userId, String resultCallbackUrl,
                                      Set<String> excludedOperations, RestorePlan plan,
                                      List<RestoredPage> restoredPages) {
            List<RebuildPage> rebuild = plan.byAction(RestoreAction.rebuild).stream()
                    .map(RestoreRun::toRebuildPage)
                    .toList();
            List<String> deleted = plan.byAction(RestoreAction.delete).stream()
                    .map(PageRestorePlan::pageId)
                    .toList();
            return new RestoreRun(operationId, restoredFrom, workspaceId, userId, resultCallbackUrl,
                    excludedOperations, rebuild, restoredPages, deleted);
        }

        private static RebuildPage toRebuildPage(PageRestorePlan page) {
            List<Kept> kept = page.keepContributions().stream()
                    .map(k -> new Kept(k.operationId(), k.documentId(), k.objectKey()))
                    .toList();
            return new RebuildPage(page.pageId(), page.contributionCount(), kept);
        }
    }
}
