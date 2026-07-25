package fruition.wikimaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMaintenanceServiceTest {

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock PipelineWikiMaintenanceRequester requester;

    private WikiMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WikiMaintenanceService(workspaceMemberRepository, requester);
    }

    @Test
    void lint_delegatesToRequesterForMember() throws Exception {
        WikiLintRequest request = new WikiLintRequest(false, true);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(requester.lint("ws_1", "user_1", request))
                .thenReturn(new ObjectMapper().readTree("{\"cluster_count\":2}"));

        var result = service.lint("ws_1", "user_1", request);

        assertThat(result.path("cluster_count").asInt()).isEqualTo(2);
    }

    @Test
    void lint_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_2")).thenReturn(false);

        assertThatThrownBy(() -> service.lint("ws_1", "user_2", new WikiLintRequest(null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
    }
}
