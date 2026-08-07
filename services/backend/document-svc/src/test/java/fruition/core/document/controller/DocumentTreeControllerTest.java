package fruition.core.document.controller;

import fruition.core.document.dto.DocumentTreeResponse;
import fruition.core.document.service.FolderService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentTreeController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class DocumentTreeControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean FolderService folderService;

    @Test
    void tree_authenticatedReturnsNestedItems() throws Exception {
        when(folderService.tree(WORKSPACE_ID, USER_ID))
                .thenReturn(new DocumentTreeResponse(List.of(
                        DocumentTreeResponse.Item.folder(
                                "33333333-3333-3333-3333-333333333333",
                                "자료",
                                0,
                                2,
                                List.of(DocumentTreeResponse.Item.document("doc_1", "메모", 0, 3))))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/document-tree")
                        .header("Authorization",
                                "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("folder"))
                .andExpect(jsonPath("$.items[0].current_version").value(2))
                .andExpect(jsonPath("$.items[0].has_children").value(true))
                .andExpect(jsonPath("$.items[0].children[0].id").value("doc_1"))
                .andExpect(jsonPath("$.items[0].children[0].current_version").value(3));
    }

    @Test
    void tree_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/document-tree"))
                .andExpect(status().isUnauthorized());
    }
}
