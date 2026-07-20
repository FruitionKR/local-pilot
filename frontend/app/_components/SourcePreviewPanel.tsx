import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { MarkdownViewer } from "./MarkdownViewer";
import { fetchWikiPage } from "../_lib/api";
import { getErrorMessage } from "../_lib/errors";
import type { SourceBlockHighlight, WikiPageDetailResponse } from "../_lib/types";

export function SourcePreviewPanel({
  title,
  pageId,
  pageType,
  documentId,
  sourceBlockHighlights,
  width,
  onResizeStart,
  fillMain = false
}: {
  title: string;
  pageId: string | null;
  pageType: string | null;
  documentId?: string | null;
  sourceBlockHighlights?: SourceBlockHighlight[];
  width: number;
  onResizeStart: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  /** 홈에서 문서가 메인 영역을 채울 때: 고정폭/리사이즈 대신 남은 영역을 채운다 */
  fillMain?: boolean;
}) {
  const [page, setPage] = useState<WikiPageDetailResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [rawMarkdown, setRawMarkdown] = useState<string | null>(null);
  const blockRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const resolvedPageType = (page?.page_type || pageType || "source").toLowerCase();
  const pageTypeLabel = resolvedPageType === "concept" ? "Concept" : "Source";
  const sourceDocuments = page?.source_documents ?? [];
  const selectedBlockHighlights = useMemo(() => sourceBlockHighlights ?? [], [sourceBlockHighlights]);
  const isMarkdownFile = !pageId && !!documentId && /\.(md|markdown)$/i.test(title);
  const isPdfOrOther = !pageId && !!documentId && !isMarkdownFile;
  const rawDocumentUrl = documentId ? `/api/documents/${documentId}/original` : null;

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
        if (!ignore) setErrorMessage(getErrorMessage(error, "Wiki page를 불러오지 못했습니다."));
      })
      .finally(() => {
        if (!ignore) setIsLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, [pageId]);

  useEffect(() => {
    if (!isMarkdownFile || !rawDocumentUrl) {
      setRawMarkdown(null);
      return;
    }

    let ignore = false;
    setIsLoading(true);
    setErrorMessage(null);

    fetch(rawDocumentUrl)
      .then((res) => {
        if (!res.ok) throw new Error(`문서를 불러오지 못했습니다. (${res.status})`);
        return res.text();
      })
      .then((text) => {
        if (!ignore) setRawMarkdown(text);
      })
      .catch((error: unknown) => {
        if (!ignore) setErrorMessage(getErrorMessage(error, "문서를 불러오지 못했습니다."));
      })
      .finally(() => {
        if (!ignore) setIsLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, [isMarkdownFile, rawDocumentUrl]);

  useEffect(() => {
    if (!isMarkdownFile || selectedBlockHighlights.length === 0 || rawMarkdown === null) return;

    const frameId = window.requestAnimationFrame(() => {
      blockRefs.current[selectedBlockHighlights[0].block_id]?.scrollIntoView({ block: "center" });
    });
    return () => window.cancelAnimationFrame(frameId);
  }, [isMarkdownFile, rawMarkdown, selectedBlockHighlights]);

  return (
    <section
      className={`source-preview-panel${fillMain ? " is-main" : ""}`}
      style={fillMain ? undefined : { width }}
      aria-label="원본문서 미리보기"
      onClick={(event) => event.stopPropagation()}
    >
      <header>
        <h2>{title}</h2>
        <span>{pageId ? pageTypeLabel : "Raw"}</span>
      </header>
      <div className="source-preview-content">
        {isMarkdownFile && isLoading && <p>문서를 불러오는 중입니다.</p>}
        {isMarkdownFile && errorMessage && <p>{errorMessage}</p>}
        {isMarkdownFile && !isLoading && !errorMessage && rawMarkdown !== null && (
          <MarkdownViewer
            markdown={rawMarkdown}
            highlightedBlocks={selectedBlockHighlights}
            onBlockRef={(blockId, node) => {
              blockRefs.current[blockId] = node;
            }}
          />
        )}
        {isPdfOrOther && rawDocumentUrl && (
          <iframe
            src={rawDocumentUrl}
            title={title}
            className="source-preview-iframe"
            style={{ width: "100%", height: "100%", border: "none" }}
          />
        )}
        {pageId && isLoading && <p>본문을 불러오는 중입니다.</p>}
        {pageId && errorMessage && <p>{errorMessage}</p>}
        {pageId && !isLoading && !errorMessage && page?.markdown && <MarkdownViewer markdown={page.markdown} />}
        {pageId && !isLoading && !errorMessage && !page?.markdown && page?.summary && <p>{page.summary}</p>}
        {pageId && !isLoading && !errorMessage && page && !page.markdown && (
          <p>본문 markdown을 찾지 못했습니다. 연결된 원본 문서 정보만 표시합니다.</p>
        )}
        {pageId && !isLoading && !errorMessage && sourceDocuments.length > 0 && (
          <div className="source-preview-meta">
            <strong>원본 문서</strong>
            {sourceDocuments.map((document) => (
              <span key={document.id}>{document.filename || document.id}</span>
            ))}
          </div>
        )}
        {!pageId && !documentId && <p>연결된 Wiki page가 없는 항목입니다.</p>}
      </div>
      {!fillMain && (
        <button
          type="button"
          className="source-preview-resize-handle"
          aria-label="원본문서 패널 폭 조절"
          onPointerDown={onResizeStart}
        />
      )}
    </section>
  );
}
