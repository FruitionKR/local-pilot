package fruition.wiki.service;

import fruition.document.repository.DocumentRepository;
import fruition.util.StorageProperties;
import fruition.wiki.dto.WikiPageRenameRequest;
import fruition.wiki.dto.WikiPageRenameResponse;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.PipelineWikiPageRequester;
import fruition.wiki.repository.WikiPageLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.workspace.repository.WorkspaceMemberRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiServiceTest {

    @Mock WikiPageRepository wikiPageRepository;
    @Mock WikiPageLinkRepository wikiPageLinkRepository;
    @Mock DocumentWikiLinkRepository documentWikiLinkRepository;
    @Mock DocumentRepository documentRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock PipelineWikiPageRequester pipelineWikiPageRequester;
    @Mock MinioClient minioClient;
    @Mock StorageProperties storageProperties;
    @InjectMocks WikiService wikiService;

    @Test
    void rename_verifiesMembershipAndDelegatesWithoutReadingWikiPages() {
        WikiPageRenameRequest request = new WikiPageRenameRequest("새 제목", true);
        WikiPageRenameResponse expected = new WikiPageRenameResponse(
                "wp_1", "concept", "새 제목", "이전 제목",
                "new-title", "old-title", true, Instant.parse("2026-07-28T00:00:00Z"));
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1"))
                .thenReturn(true);
        when(pipelineWikiPageRequester.rename("ws_1", "user_1", "wp_1", request))
                .thenReturn(expected);

        WikiPageRenameResponse response = wikiService.rename("ws_1", "user_1", "wp_1", request);

        assertThat(response).isEqualTo(expected);
        verify(pipelineWikiPageRequester).rename("ws_1", "user_1", "wp_1", request);
        verifyNoInteractions(wikiPageRepository);
    }

    @Test
    void rename_nonMemberDoesNotCallPipeline() {
        WikiPageRenameRequest request = new WikiPageRenameRequest("새 제목", false);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1"))
                .thenReturn(false);

        assertThatThrownBy(() -> wikiService.rename("ws_1", "user_1", "wp_1", request))
                .isInstanceOf(RuntimeException.class);

        verify(pipelineWikiPageRequester, never()).rename("ws_1", "user_1", "wp_1", request);
        verifyNoInteractions(wikiPageRepository);
    }
}
