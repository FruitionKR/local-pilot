package fruition.aihistory.service;

import fruition.document.dto.MarkdownDiff;
import fruition.document.service.MarkdownDiffService;
import fruition.wiki.domain.WikiPageVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 변경내역에 남길 증감 줄 수를 센다. 목록 화면이 계산 없이 바로 보여주기 위한 값이다. */
@Component
public class WikiLineCounter {

    private static final Logger log = LoggerFactory.getLogger(WikiLineCounter.class);

    private final MarkdownDiffService markdownDiffService;

    public WikiLineCounter(MarkdownDiffService markdownDiffService) {
        this.markdownDiffService = markdownDiffService;
    }

    /** 계산이 실패해도 적재를 막지 않는다. 줄 수는 없어도 되는 값이다. */
    public LineCount count(String pageId, WikiPageVersion previous, String markdown,
                           Long beforeRevision, long afterRevision) {
        if (previous == null) {
            return new LineCount(null, null);
        }
        try {
            MarkdownDiff diff = markdownDiffService.diff(
                    beforeRevision, previous.getMarkdown(), afterRevision, markdown);
            return new LineCount(diff.additions(), diff.deletions());
        } catch (RuntimeException e) {
            log.warn("[Wiki 줄 수 계산 생략] pageId={} reason={}", pageId, e.getMessage());
            return new LineCount(null, null);
        }
    }

    public record LineCount(Integer additions, Integer deletions) {}
}
