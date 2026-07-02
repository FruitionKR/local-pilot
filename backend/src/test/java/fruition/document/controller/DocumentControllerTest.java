package fruition.document.controller;

import fruition.document.dto.DocumentBlockResponse;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.service.DocumentService;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.util.GlobalExceptionHandler;
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

@WebMvcTest(DocumentController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class DocumentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean DocumentService documentService;

    @Test
    void getBlocks_existingDocument_returnsDocumentIdAndBlocksInOrder() throws Exception {
        DocumentBlocksResponse response = new DocumentBlocksResponse("doc_1f9a74af", List.of(
                new DocumentBlockResponse("B0005", "원본 문서의 다섯 번째 block 본문"),
                new DocumentBlockResponse("B0006", "원본 문서의 여섯 번째 block 본문")
        ));
        when(documentService.blocks("doc_1f9a74af")).thenReturn(response);

        mockMvc.perform(get("/api/documents/doc_1f9a74af/blocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("doc_1f9a74af"))
                .andExpect(jsonPath("$.blocks[0].block_id").value("B0005"))
                .andExpect(jsonPath("$.blocks[0].text").value("원본 문서의 다섯 번째 block 본문"))
                .andExpect(jsonPath("$.blocks[1].block_id").value("B0006"));
    }

    @Test
    void getBlocks_unknownDocument_returns404() throws Exception {
        when(documentService.blocks("doc_unknown")).thenThrow(new DocumentNotFoundException("doc_unknown"));

        mockMvc.perform(get("/api/documents/doc_unknown/blocks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }
}
