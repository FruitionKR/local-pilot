package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.security.AgentServiceTokenFilter;
import fruition.core.agent.service.AgentToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentToolControllerTest {

    private AgentToolService toolService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        toolService = mock(AgentToolService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentToolController(toolService))
                .addFilters(new AgentServiceTokenFilter("test-agent-token"))
                .build();
    }

    @Test
    void read_forwardsAuthenticatedRequest() throws Exception {
        when(toolService.read(eq("list_root_items"), any(AgentToolReadRequest.class)))
                .thenReturn(Map.of("items", "ok"));

        mockMvc.perform(post("/internal/agent/tools/read/list_root_items")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run-1","workspace_id":"workspace-1",
                                 "user_id":"user-1","arguments":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value("ok"));
    }

    @Test
    void execute_rejectsInvalidTokenBeforeMalformedBodyBinding() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/execute/move_document")
                        .header("X-Agent-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(toolService);
    }

    @Test
    void read_rejectsMissingTokenBeforeMalformedBodyBinding() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/read/list_root_items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(toolService);
    }

    @Test
    void execute_rejectsMalformedBodyWithValidToken() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/execute/move_document")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(toolService);
    }
}
