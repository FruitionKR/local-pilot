package fruition.access.workspace.service;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.dto.WorkspaceAiModelRequest;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceAiModelServiceTest {
    @Mock WorkspaceRepository workspaceRepository;

    private WorkspaceAiModelService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new WorkspaceAiModelService(
                workspaceRepository, new AiModelCatalog("openai,gemini,claude"));
        workspace = new Workspace("ws_1", "테스트");
    }

    @Test
    void getInternal_returnsDefaultSetting() {
        when(workspaceRepository.findById("ws_1")).thenReturn(Optional.of(workspace));

        var response = service.getInternal("ws_1");

        assertThat(response.ingestLint().provider()).isEqualTo("gemini");
        assertThat(response.ingestLint().model()).isEqualTo("gemini-3.1-flash-lite");
    }

    @Test
    void updateInternal_selectsEnabledModel() {
        when(workspaceRepository.findById("ws_1")).thenReturn(Optional.of(workspace));

        var response = service.updateInternal("ws_1", new WorkspaceAiModelRequest(
                new WorkspaceAiModelRequest.AiModelSelection("claude", "claude-sonnet-5")));

        assertThat(response.ingestLint().provider()).isEqualTo("claude");
        assertThat(response.ingestLint().model()).isEqualTo("claude-sonnet-5");
    }
}
