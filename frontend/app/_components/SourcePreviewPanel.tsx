import { useEffect, useState, type PointerEvent as ReactPointerEvent } from "react";
import { MarkdownViewer } from "./MarkdownViewer";
import { fetchWikiPage } from "../_lib/api";
import type { WikiPageDetailResponse } from "../_lib/types";

export function SourcePreviewPanel({
  title,
  pageId,
  pageType,
  width,
  onResizeStart
}: {
  title: string;
  pageId: string | null;
  pageType: string | null;
  width: number;
  onResizeStart: (event: ReactPointerEvent<HTMLButtonElement>) => void;
}) {
  const [page, setPage] = useState<WikiPageDetailResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const resolvedPageType = (page?.page_type || pageType || "source").toLowerCase();
  const pageTypeLabel = resolvedPageType === "concept" ? "Concept" : "Source";
  const sourceDocuments = page?.source_documents ?? [];

  useEffect(() => {
    if (!pageId) {
      setPage(null);
      setErrorMessage(null);
      return;
    }

    let ignore = false;
    setIsLoading(true);
    setErrorMessage(null);

    fetchWikiPage(pageId)
      .then((nextPage) => {
        if (!ignore) setPage(nextPage);
      })
      .catch((error) => {
        if (!ignore) setErrorMessage(error instanceof Error ? error.message : "Wiki page를 불러오지 못했습니다.");
      })
      .finally(() => {
        if (!ignore) setIsLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, [pageId]);

  return (
    <section
      className="source-preview-panel"
      style={{ width }}
      aria-label="원본문서 미리보기"
      onClick={(event) => event.stopPropagation()}
    >
      <header>
        <h2>{title}</h2>
        <span>{pageTypeLabel}</span>
      </header>
      <div className="source-preview-content">
        {isLoading && <p>본문을 불러오는 중입니다.</p>}
        {errorMessage && <p>{errorMessage}</p>}
        {!isLoading && !errorMessage && page?.markdown && <MarkdownViewer markdown={page.markdown} />}
        {!isLoading && !errorMessage && !page?.markdown && page?.summary && <p>{page.summary}</p>}
        {!isLoading && !errorMessage && page && !page.markdown && (
          <p>본문 markdown을 찾지 못했습니다. 연결된 원본 문서 정보만 표시합니다.</p>
        )}
        {!isLoading && !errorMessage && sourceDocuments.length > 0 && (
          <div className="source-preview-meta">
            <strong>원본 문서</strong>
            {sourceDocuments.map((document) => (
              <span key={document.id}>{document.filename || document.id}</span>
            ))}
          </div>
        )}
        {!pageId && <p>연결된 Wiki page가 없는 항목입니다.</p>}
      </div>
      <button
        type="button"
        className="source-preview-resize-handle"
        aria-label="원본문서 패널 폭 조절"
        onPointerDown={onResizeStart}
      />
    </section>
  );
}
