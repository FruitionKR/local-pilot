package fruition.core.aihistory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.OperationResultResponse;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.exception.OperationPayloadConflictException;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * llmPipeline이 호출하는 내부 콜백. 사용자 인증이 아니라 공유 토큰으로 지킨다.
 *
 * <p>토큰을 통과하기 전에는 서비스에 닿지도 않아야 한다. 저장소 객체를 읽는 것이 그 뒤이기 때문이다.
 */
@WebMvcTest(OperationCallbackController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class OperationCallbackControllerTest {

    private static final String OPERATION_ID = "op_a2";
    private static final String URL = "/api/ai-operations/" + OPERATION_ID + "/result";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean OperationIngestService ingestService;

    @Value("${app.internal.callback-token}")
    String internalToken;

    @Test
    @DisplayName("토큰이 맞으면 확정된 상태를 그대로 돌려준다")
    void acceptsWithValidToken() throws Exception {
        when(ingestService.accept(eq(OPERATION_ID), any()))
                .thenReturn(new OperationResultResponse(OPERATION_ID, "partially_succeeded", 2));

        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation_id").value(OPERATION_ID))
                // 성공 고정이 아니라 Backend가 실제로 확정한 값이어야 한다.
                .andExpect(jsonPath("$.status").value("partially_succeeded"))
                .andExpect(jsonPath("$.recorded_changes").value(2));
    }

    @Test
    @DisplayName("토큰이 없으면 401이고 서비스에 닿지 않는다")
    void rejectsWithoutToken() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CALLBACK_TOKEN"));

        verify(ingestService, never()).accept(any(), any());
    }

    @Test
    @DisplayName("토큰이 틀리면 401이고 서비스에 닿지 않는다")
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isUnauthorized());

        verify(ingestService, never()).accept(any(), any());
    }

    @Test
    @DisplayName("사용자 JWT로는 통과하지 못한다")
    void userTokenIsNotAccepted() throws Exception {
        mockMvc.perform(post(URL)
                        .header("Authorization", "Bearer something")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isUnauthorized());

        verify(ingestService, never()).accept(any(), any());
    }

    @Test
    @DisplayName("등록되지 않은 작업은 404")
    void unknownOperationIsNotFound() throws Exception {
        when(ingestService.accept(any(), any()))
                .thenThrow(new OperationNotFoundException(OPERATION_ID));

        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AI_OPERATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 작업에 다른 결과가 오면 409")
    void differentPayloadIsConflict() throws Exception {
        when(ingestService.accept(any(), any()))
                .thenThrow(new OperationPayloadConflictException(OPERATION_ID));

        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AI_OPERATION_PAYLOAD_CONFLICT"));
    }

    @Test
    @DisplayName("경로·해시 검증 실패는 422라 llmPipeline이 다시 쓰고 재전송한다")
    void invalidPayloadIsUnprocessable() throws Exception {
        when(ingestService.accept(any(), any()))
                .thenThrow(new InvalidCallbackPayloadException("본문 해시가 일치하지 않습니다."));

        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_CALLBACK_PAYLOAD"));
    }

    @Test
    @DisplayName("changed_pages가 없으면 400")
    void missingChangedPagesIsBadRequest() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation_id\":\"" + OPERATION_ID + "\",\"status\":\"succeeded\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verify(ingestService, never()).accept(any(), any());
    }

    @Test
    @DisplayName("재조립 결과의 failed_pages를 받아 넘긴다")
    void acceptsRebuildResultWithFailedPages() throws Exception {
        when(ingestService.accept(eq(OPERATION_ID), any()))
                .thenReturn(new OperationResultResponse(OPERATION_ID, "partially_succeeded", 3));

        String body = """
                { "operation_id": "%s", "operation_type": "ingest_restore",
                  "status": "partially_succeeded",
                  "changed_pages": [ { "page_id": "wp_C3", "markdown_key": "wiki/a.md",
                                       "content_hash": "sha256:abc" } ],
                  "failed_pages": [ { "page_id": "wp_C6", "reason": "contribution_missing" } ] }
                """.formatted(OPERATION_ID);

        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded_changes").value(3));
    }

    @Test
    @DisplayName("재조립 결과의 링크 변경과 실패 작업을 손실 없이 받아 넘긴다")
    void acceptsRestoreLinkChangesAndFailedActions() throws Exception {
        when(ingestService.accept(eq(OPERATION_ID), any()))
                .thenReturn(new OperationResultResponse(OPERATION_ID, "partially_succeeded", 2));

        String body = """
                { "operation_id": "%s", "operation_type": "lint_restore",
                  "status": "partially_succeeded", "changed_pages": [],
                  "deleted_pages": ["wp_C7"],
                  "link_changes": {
                    "removed_links": [ { "source": "wp_C3", "target": "wp_C7", "relation": "related" } ],
                    "restored_links": [ { "source": "wp_C3", "target": "wp_C4", "relation": "supports" } ]
                  },
                  "failed_actions": [ { "action": "delete_page", "resource_id": "wp_C8",
                                         "reason": "page_not_found" } ] }
                """.formatted(OPERATION_ID);

        mockMvc.perform(post(URL)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<OperationResultRequest> captor = ArgumentCaptor.forClass(OperationResultRequest.class);
        verify(ingestService).accept(eq(OPERATION_ID), captor.capture());
        OperationResultRequest request = captor.getValue();
        assertThat(request.deletedPagesOrEmpty()).containsExactly("wp_C7");
        assertThat(request.linkChangesOrEmpty().removedLinks()).hasSize(1);
        assertThat(request.linkChangesOrEmpty().restoredLinks()).hasSize(1);
        assertThat(request.failedActionsOrEmpty()).hasSize(1);
    }

    private String payload() {
        return """
                { "operation_id": "%s", "operation_type": "ingest", "status": "succeeded",
                  "changed_pages": [ { "page_id": "wp_C3", "markdown_key": "wiki/a.md",
                                       "content_hash": "sha256:abc" } ] }
                """.formatted(OPERATION_ID);
    }
}
