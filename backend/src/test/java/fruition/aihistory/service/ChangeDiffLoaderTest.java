package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.ResourceType;
import fruition.document.domain.DocumentContentVersion;
import fruition.document.domain.DocumentContentVersionId;
import fruition.document.exception.MarkdownDiffTooLargeException;
import fruition.document.repository.DocumentContentVersionRepository;
import fruition.document.service.MarkdownDiffService;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.domain.WikiPageVersionId;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그 상세에 실을 변경분. 한 항목이 실패해도 나머지는 정상이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeDiffLoaderTest {

    private static final String OPERATION = "op_a2";
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Mock WikiPageVersionRepository wikiVersionRepository;
    @Mock DocumentContentVersionRepository documentVersionRepository;

    private ChangeDiffLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ChangeDiffLoader(wikiVersionRepository, documentVersionRepository,
                new MarkdownDiffService());
    }

    @Test
    @DisplayName("Wiki 페이지 갱신은 두 revision 본문을 읽어 변경분을 만든다")
    void computesWikiDiff() {
        givenWikiVersion("wp_C3", 3, "# 제목\n첫 줄\n");
        givenWikiVersion("wp_C3", 4, "# 제목\n첫 줄\n둘째 줄\n");

        ChangeDiffLoader.Diff diff = loader.load(
                change(ResourceType.wiki_page, "wp_C3", 3L, 4L, ChangeType.updated));

        assertThat(diff.hunks()).isNotEmpty();
        assertThat(diff.tooLarge()).isFalse();
    }

    @Test
    @DisplayName("문서 편집은 문서 버전 테이블에서 읽는다")
    void computesDocumentDiff() {
        givenDocumentVersion("doc_A", 5, "원래 문단\n");
        givenDocumentVersion("doc_A", 6, "다듬은 문단\n");

        ChangeDiffLoader.Diff diff = loader.load(
                change(ResourceType.document, "doc_A", 5L, 6L, ChangeType.updated));

        assertThat(diff.hunks()).isNotEmpty();
        verify(wikiVersionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("새로 만든 리소스는 비교할 짝이 없어 변경분을 담지 않는다")
    void skipsWhenCreated() {
        ChangeDiffLoader.Diff diff = loader.load(
                change(ResourceType.wiki_page, "wp_C3", null, 1L, ChangeType.created));

        assertThat(diff.hunks()).isNull();
        assertThat(diff.tooLarge()).isFalse();
        verify(wikiVersionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("삭제·위임·재작성실패는 after_revision이 없어 건너뛴다")
    void skipsWhenNoAfterRevision() {
        assertThat(loader.load(change(ResourceType.wiki_page, "wp_C6", 4L, null,
                ChangeType.deleted)).hunks()).isNull();
        assertThat(loader.load(change(ResourceType.wiki_page, "wp_C3", 4L, null,
                ChangeType.delegated)).hunks()).isNull();
        assertThat(loader.load(change(ResourceType.wiki_page, "wp_C7", 4L, null,
                ChangeType.rebuild_failed)).hunks()).isNull();
    }

    @Test
    @DisplayName("버전 행이 없으면 조용히 건너뛴다")
    void skipsWhenVersionMissing() {
        givenWikiVersion("wp_C3", 3, "# 제목\n");
        when(wikiVersionRepository.findById(new WikiPageVersionId("wp_C3", 4)))
                .thenReturn(Optional.empty());

        ChangeDiffLoader.Diff diff = loader.load(
                change(ResourceType.wiki_page, "wp_C3", 3L, 4L, ChangeType.updated));

        assertThat(diff.hunks()).isNull();
        assertThat(diff.tooLarge()).isFalse();
    }

    @Test
    @DisplayName("계산이 거부되면 그 항목만 표시하고 예외를 올리지 않는다")
    void marksTooLargeInsteadOfThrowing() {
        MarkdownDiffService refusing = new MarkdownDiffService() {
            @Override
            public fruition.document.dto.MarkdownDiff diff(long fromVersion, String before,
                                                           long toVersion, String after) {
                throw new MarkdownDiffTooLargeException("너무 큽니다.");
            }
        };
        loader = new ChangeDiffLoader(wikiVersionRepository, documentVersionRepository, refusing);
        givenWikiVersion("wp_C3", 3, "가");
        givenWikiVersion("wp_C3", 4, "나");

        ChangeDiffLoader.Diff diff = loader.load(
                change(ResourceType.wiki_page, "wp_C3", 3L, 4L, ChangeType.updated));

        assertThat(diff.hunks()).isNull();
        assertThat(diff.tooLarge()).isTrue();
    }

    // --- helpers ---

    private OperationChange change(ResourceType type, String resourceId,
                                   Long before, Long after, ChangeType changeType) {
        return new OperationChange(OPERATION, type, resourceId, before, after,
                changeType, null, null, null);
    }

    private void givenWikiVersion(String pageId, long revision, String markdown) {
        when(wikiVersionRepository.findById(new WikiPageVersionId(pageId, revision)))
                .thenReturn(Optional.of(new WikiPageVersion(pageId, revision, 1, markdown,
                        "wiki/key.md", "sha256:x", OPERATION, "user_1", NOW)));
    }

    private void givenDocumentVersion(String documentId, long version, String markdown) {
        when(documentVersionRepository.findById(new DocumentContentVersionId(documentId, version)))
                .thenReturn(Optional.of(new DocumentContentVersion(documentId, version, markdown,
                        "sha256:x", "user_1", NOW)));
    }
}
