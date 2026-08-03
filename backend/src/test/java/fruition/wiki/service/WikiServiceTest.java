package fruition.wiki.service;

import fruition.document.repository.DocumentRepository;
import fruition.util.StorageProperties;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageLink;
import fruition.wiki.domain.WikiPageStatus;
import fruition.wiki.domain.WikiPageType;
import fruition.wiki.dto.WikiGraphResponse;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.dto.WikiPageDetailResponse;
import fruition.wiki.dto.WikiPageRenameRequest;
import fruition.wiki.dto.WikiPageRenameResponse;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.PipelineWikiPageRequester;
import fruition.wiki.repository.WikiPageLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.workspace.repository.WorkspaceMemberRepository;
import io.minio.MinioClient;
import fruition.wiki.exception.WikiPageNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class WikiServiceTest {

    @Mock WikiPageRepository wikiPageRepository;
    @Mock WikiPageLinkRepository wikiPageLinkRepository;
    @Mock DocumentWikiLinkRepository documentWikiLinkRepository;
    @Mock DocumentRepository documentRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock PipelineWikiPageRequester pipelineWikiPageRequester;
    @Mock fruition.wiki.repository.WikiPageVersionRepository versionRepository;
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
    @DisplayName("그래프는 소프트 삭제된 페이지를 제외하고 조회한다")
    void findGraph_excludesDeletedPages() {
        givenMember();
        List<WikiPage> alive = List.of(page("wp_1", WikiPageStatus.active));
        // 삭제된 wp_2로 가는 링크. 대상이 page 집합에 없으므로 간선도 빠진다.
        List<WikiPageLink> links = List.of(link("wp_1", "wp_2"));
        when(wikiPageRepository.findAliveByWorkspaceId("ws_1"))
                .thenReturn(alive);
        when(wikiPageLinkRepository.findAllByIdFromPageIdIn(java.util.Set.of("wp_1")))
                .thenReturn(links);

        WikiGraphResponse response = wikiService.findGraph("ws_1", "user_1");

        assertThat(response.nodes()).extracting(n -> n.id()).containsExactly("wp_1");
        assertThat(response.edges()).isEmpty();
    }

    @Test
    @DisplayName("삭제된 페이지 상세는 404다")
    void findById_deletedPageNotFound() {
        givenMember();
        when(wikiPageRepository.findAliveByIdAndWorkspaceId("wp_1", "ws_1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> wikiService.findById("ws_1", "user_1", "wp_1"))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    @Test
    @DisplayName("받치는 기여가 사라진 대상은 빼고, 대상 자체가 없는 링크는 남긴다")
    void findById_dropsLinksToDeletedPages() {
        givenMember();
        WikiPage self = page("wp_1", WikiPageStatus.active);
        List<WikiPageLink> outLinks = List.of(
                link("wp_1", "wp_deleted"), link("wp_1", "wp_alive"), link("wp_1", "wp_missing"));
        List<WikiPage> targets = List.of(
                page("wp_deleted", WikiPageStatus.deleted),
                page("wp_alive", WikiPageStatus.active));
        when(wikiPageRepository.findAliveByIdAndWorkspaceId("wp_1", "ws_1"))
                .thenReturn(Optional.of(self));
        when(wikiPageRepository.findAliveIds(List.of("wp_deleted", "wp_alive", "wp_missing")))
                .thenReturn(List.of("wp_alive"));
        when(documentWikiLinkRepository.findAllByIdWikiPageId("wp_1")).thenReturn(List.of());
        when(wikiPageLinkRepository.findAllByIdFromPageId("wp_1")).thenReturn(outLinks);
        when(wikiPageRepository.findAllById(List.of("wp_deleted", "wp_alive", "wp_missing")))
                .thenReturn(targets);

        WikiPageDetailResponse response = wikiService.findById("ws_1", "user_1", "wp_1");

        assertThat(response.relatedPages()).extracting(r -> r.id())
                .containsExactly("wp_alive", "wp_missing");
    }

    @Test
    @DisplayName("현재 본문은 최신 revision 에서 읽는다")
    void readsCurrentMarkdownFromLatestRevision() {
        givenMember();
        WikiPage self = page("wp_1", WikiPageStatus.active);
        when(wikiPageRepository.findAliveByIdAndWorkspaceId("wp_1", "ws_1"))
                .thenReturn(Optional.of(self));
        when(documentWikiLinkRepository.findAllByIdWikiPageId("wp_1")).thenReturn(List.of());
        when(wikiPageLinkRepository.findAllByIdFromPageId("wp_1")).thenReturn(List.of());
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1"))
                .thenReturn(Optional.of(new WikiPageVersion("wp_1", 3, 2, "# 최신 본문",
                        "wiki/key.md", "sha256:x", "op_a2", "user_1",
                        java.time.Instant.parse("2026-08-03T00:00:00Z"))));

        WikiPageDetailResponse response = wikiService.findById("ws_1", "user_1", "wp_1");

        assertThat(response.markdown()).isEqualTo("# 최신 본문");
        // wiki_pages.markdown_uri 로 저장소를 읽지 않는다.
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("revision 기록이 없는 예전 페이지는 markdown_uri 로 폴백한다")
    void fallsBackToMarkdownUriWhenNoRevision() throws Exception {
        givenMember();
        WikiPage self = page("wp_1", WikiPageStatus.active);
        when(self.getMarkdownUri()).thenReturn("wiki/legacy.md");
        when(wikiPageRepository.findAliveByIdAndWorkspaceId("wp_1", "ws_1"))
                .thenReturn(Optional.of(self));
        when(documentWikiLinkRepository.findAllByIdWikiPageId("wp_1")).thenReturn(List.of());
        when(wikiPageLinkRepository.findAllByIdFromPageId("wp_1")).thenReturn(List.of());
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1"))
                .thenReturn(Optional.empty());
        when(storageProperties.getBucket()).thenReturn("fruition");

        wikiService.findById("ws_1", "user_1", "wp_1");

        // 저장소를 읽으려 시도한다. 실패하면 기존대로 markdown 이 null 로 내려간다.
        verify(minioClient, atLeastOnce()).getObject(any());
    }

    // --- helpers ---

    private void givenMember() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1"))
                .thenReturn(true);
    }

    /** 엔티티에 전체 생성자가 없어 조회 결과를 흉내 낼 때는 mock을 쓴다. */
    private WikiPage page(String id, WikiPageStatus status) {
        WikiPage page = mock(WikiPage.class);
        when(page.getId()).thenReturn(id);
        when(page.getStatus()).thenReturn(status);
        when(page.getPageType()).thenReturn(WikiPageType.concept);
        return page;
    }

    private WikiPageLink link(String from, String to) {
        WikiPageLink link = mock(WikiPageLink.class);
        when(link.getFromPageId()).thenReturn(from);
        when(link.getToPageId()).thenReturn(to);
        return link;
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
