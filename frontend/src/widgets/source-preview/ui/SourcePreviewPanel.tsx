import { MoreHorizontal, PanelRight } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { MarkdownViewer } from "@/shared/ui/MarkdownViewer";
import { DynamicNoteEditor } from "@/features/note-editing/ui/DynamicNoteEditor";
import { fetchDocumentOriginal } from "@/entities/document";
import { fetchWikiPage } from "@/entities/wiki";
import { fetchNoteDraft } from "@/features/note-editing";
import { getErrorMessage } from "@/shared/lib/errors";
import { buildMarkdownDocumentFilename, getMarkdownDocumentTitle, splitEditableNoteMarkdown } from "@/entities/document/lib/note";
import { cx } from "@/shared/lib/classNames";
import styles from "./SourcePreviewPanel.module.css";
import type { ActiveMarkdownEditContext } from "@/features/agent-chat/lib/markdownEditContext";
import type { SourceBlockHighlight } from "@/entities/document";
import type { NoteEditState, NoteSaveStatus } from "@/entities/tree";
import type { WikiPageDetailResponse } from "@/entities/wiki";

const SAVE_STATUS_LABELS: Partial<Record<NoteSaveStatus, string>> = {
  dirty: "변경됨",
  saving: "저장 중",
  error: "저장 실패",
  conflict: "저장 충돌"
};

export function SourcePreviewPanel({
  title,
  pageId,
  pageType,
  documentId,
  sourceBlockHighlights,
  width,
  onResizeStart,
  onMarkdownEditContextChange,
  onRequestLint,
  onRenameDocument,
  onNoteEditStateChange,
  parentLabel = "업로드 문서",
  editedAt = null,
  onExitDocument,
  isAgentPanelOpen,
  onOpenAgentPanel,
  fillMain = false
}: {
  title: string;
  pageId: string | null;
  pageType: string | null;
  documentId?: string | null;
  sourceBlockHighlights?: SourceBlockHighlight[];
  width: number;
  onResizeStart: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  onMarkdownEditContextChange?: (context: ActiveMarkdownEditContext | null) => void;
  onRequestLint?: (context: ActiveMarkdownEditContext) => void;
  onRenameDocument?: (documentId: string, filename: string) => Promise<void>;
  onNoteEditStateChange?: (documentId: string, state: NoteEditState | null) => void;
  parentLabel?: string;
  editedAt?: string | null;
  onExitDocument?: () => void;
  isAgentPanelOpen: boolean;
  onOpenAgentPanel?: () => void;
  /** 홈에서 문서가 메인 영역을 채울 때: 고정폭/리사이즈 대신 남은 영역을 채운다 */
  fillMain?: boolean;
}) {
  const [page, setPage] = useState<WikiPageDetailResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [rawMarkdown, setRawMarkdown] = useState<string | null>(null);
  const [noteContentVersion, setNoteContentVersion] = useState(0);
  const [rawDocumentUrl, setRawDocumentUrl] = useState<string | null>(null);
  const [titleInput, setTitleInput] = useState(() => getMarkdownDocumentTitle(title));
  const [isRenaming, setIsRenaming] = useState(false);
  const [renameError, setRenameError] = useState<string | null>(null);
  const [sourceMode, setSourceMode] = useState(false);
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);
  const [noteSaveStatus, setNoteSaveStatus] = useState<NoteSaveStatus>("saved");
  const [noteSaveError, setNoteSaveError] = useState<string | null>(null);
  const [needsReview, setNeedsReview] = useState(false);
  const [activeEditContext, setActiveEditContext] = useState<ActiveMarkdownEditContext | null>(null);
  const blockRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const optionsRef = useRef<HTMLDivElement | null>(null);
  const resolvedPageType = (page?.page_type || pageType || "source").toLowerCase();
  const pageTypeLabel = resolvedPageType === "concept" ? "Concept" : "Source";
  const sourceDocuments = page?.source_documents ?? [];
  const selectedBlockHighlights = useMemo(() => sourceBlockHighlights ?? [], [sourceBlockHighlights]);
  const isMarkdownFile = !pageId && !!documentId && /\.(md|markdown)$/i.test(title);
  const isPdfOrOther = !pageId && !!documentId && !isMarkdownFile;
  const visibleTitle = isMarkdownFile ? getMarkdownDocumentTitle(title) : title;
  const saveStatusLabel = SAVE_STATUS_LABELS[noteSaveStatus];
  const editableNote = useMemo(() => {
    if (rawMarkdown === null || !documentId) return null;
    return splitEditableNoteMarkdown(rawMarkdown) ?? {
      marker: `<!-- fruition-note: ${documentId} -->`,
      body: rawMarkdown
    };
  }, [documentId, rawMarkdown]);
  const lastEditedLabel = useMemo(() => {
    if (!editedAt) return "마지막 편집";
    const date = new Date(editedAt);
    if (Number.isNaN(date.getTime())) return "마지막 편집";
    return `${new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric" }).format(date)} 마지막 편집`;
  }, [editedAt]);

  useEffect(() => {
    setTitleInput(getMarkdownDocumentTitle(title));
    setRenameError(null);
  }, [title]);

  useEffect(() => {
    setSourceMode(false);
    setIsOptionsOpen(false);
    setNoteSaveStatus("saved");
    setNoteSaveError(null);
    setNeedsReview(false);
    setActiveEditContext(null);
  }, [documentId]);

  useEffect(() => {
    if (!isOptionsOpen) return;

    function closeOptions(event: MouseEvent) {
      if (!optionsRef.current?.contains(event.target as Node)) setIsOptionsOpen(false);
    }
    function closeOptionsWithEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setIsOptionsOpen(false);
    }

    window.addEventListener("mousedown", closeOptions);
    window.addEventListener("keydown", closeOptionsWithEscape);
    return () => {
      window.removeEventListener("mousedown", closeOptions);
      window.removeEventListener("keydown", closeOptionsWithEscape);
    };
  }, [isOptionsOpen]);

  useEffect(() => {
    if (!isMarkdownFile || !documentId) return;
    onNoteEditStateChange?.(documentId, { saveStatus: noteSaveStatus, needsReview });
  }, [documentId, isMarkdownFile, needsReview, noteSaveStatus, onNoteEditStateChange]);

  useEffect(() => () => {
    if (documentId) onNoteEditStateChange?.(documentId, null);
  }, [documentId, onNoteEditStateChange]);

  const handleMarkdownEditContextChange = useCallback((context: ActiveMarkdownEditContext | null) => {
    setActiveEditContext(context);
    onMarkdownEditContextChange?.(context);
  }, [onMarkdownEditContextChange]);

  const handleSaveStatusChange = useCallback((status: NoteSaveStatus, message: string | null) => {
    setNoteSaveStatus(status);
    setNoteSaveError(message);
  }, []);

  const handleContentChanged = useCallback((body: string) => {
    setNeedsReview(true);
  }, []);

  async function commitTitle() {
    if (!isMarkdownFile || !documentId || !onRenameDocument || isRenaming) return;
    const nextFilename = buildMarkdownDocumentFilename(titleInput, title);
    if (nextFilename === title) {
      setTitleInput(getMarkdownDocumentTitle(title));
      return;
    }
    setIsRenaming(true);
    setRenameError(null);
    try {
      await onRenameDocument(documentId, nextFilename);
    } catch (error) {
      setTitleInput(getMarkdownDocumentTitle(title));
      setRenameError(getErrorMessage(error, "문서 이름을 변경하지 못했습니다."));
    } finally {
      setIsRenaming(false);
    }
  }


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
    if (pageId || !documentId) {
      setRawMarkdown(null);
      setNoteContentVersion(0);
      setRawDocumentUrl(null);
      return;
    }

    let ignore = false;
    let objectUrl: string | null = null;
    setIsLoading(true);
    setErrorMessage(null);
    setRawMarkdown(null);
    setNoteContentVersion(0);
    setRawDocumentUrl(null);

    const loadDocument = async () => {
      if (isMarkdownFile) {
        const blob = await fetchDocumentOriginal(documentId);
        const text = await blob.text();
        const draft = await fetchNoteDraft(documentId);
        if (!ignore) {
          setRawMarkdown(draft ? draft.markdown : text);
          setNoteContentVersion(draft ? draft.content_version : 0);
        }
        return;
      }

      const blob = await fetchDocumentOriginal(documentId);
      objectUrl = URL.createObjectURL(blob);
      if (ignore) {
        URL.revokeObjectURL(objectUrl);
        objectUrl = null;
        return;
      }
      setRawDocumentUrl(objectUrl);
    };

    void loadDocument()
      .catch((error: unknown) => {
        if (!ignore) setErrorMessage(getErrorMessage(error, "문서를 불러오지 못했습니다."));
      })
      .finally(() => {
        if (!ignore) setIsLoading(false);
      });

    return () => {
      ignore = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [documentId, isMarkdownFile, pageId]);

  useEffect(() => {
    if (!isMarkdownFile || selectedBlockHighlights.length === 0 || rawMarkdown === null) return;

    const frameId = window.requestAnimationFrame(() => {
      blockRefs.current[selectedBlockHighlights[0].block_id]?.scrollIntoView({ block: "center" });
    });
    return () => window.cancelAnimationFrame(frameId);
  }, [isMarkdownFile, rawMarkdown, selectedBlockHighlights]);

  return (
    <section
      className={cx(styles["source-preview-panel"], fillMain && styles["is-main"])}
      style={fillMain ? undefined : { width }}
      aria-label="원본문서 미리보기"
      onClick={(event) => event.stopPropagation()}
    >
      <header className={styles["source-preview-topbar"]}>
        <nav aria-label="문서 위치">
          <button type="button" onClick={onExitDocument}>{parentLabel}</button>
          <span aria-hidden="true">/</span>
          <strong>{title}</strong>
        </nav>
        <div className={styles["source-preview-actions"]}>
          {saveStatusLabel && (
            <span
              className={cx(styles["source-preview-save-status"], styles[`is-${noteSaveStatus}`])}
              role={noteSaveStatus === "error" || noteSaveStatus === "conflict" ? "alert" : "status"}
              title={noteSaveError ?? undefined}
            >
              {saveStatusLabel}
            </span>
          )}
          <span>{lastEditedLabel}</span>
          {!isAgentPanelOpen && (
            <button type="button" aria-label="AI 사이드바 열기" onClick={onOpenAgentPanel}>
              <PanelRight size={14} />
            </button>
          )}
          <div className={styles["source-preview-options"]} ref={optionsRef}>
            <button
              type="button"
              aria-label="문서 옵션"
              aria-expanded={isOptionsOpen}
              onClick={() => setIsOptionsOpen((open) => !open)}
            >
              <MoreHorizontal size={16} />
            </button>
            {isOptionsOpen && isMarkdownFile && (
              <div className={styles["source-preview-options-menu"]}>
                <button
                  type="button"
                  onClick={() => {
                    setSourceMode((current) => !current);
                    setIsOptionsOpen(false);
                  }}
                >
                  {sourceMode ? "자동 미리보기로 전환" : "Markdown 원문 보기"}
                </button>
              </div>
            )}
          </div>
        </div>
      </header>
      <div className={styles["source-preview-document"]}>
        <div className={styles["source-preview-content"]}>
        <header className={styles["source-preview-heading"]}>
          {isMarkdownFile ? (
            <input
              className={styles["source-preview-title-input"]}
              aria-label="문서 이름"
              value={titleInput}
              disabled={isRenaming}
              spellCheck={false}
              onChange={(event) => setTitleInput(event.target.value)}
              onBlur={() => void commitTitle()}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.currentTarget.blur();
                } else if (event.key === "Escape") {
                  setTitleInput(visibleTitle);
                  event.currentTarget.blur();
                }
              }}
            />
          ) : (
            <h2>{visibleTitle}</h2>
          )}
          {!fillMain && <span>{pageId ? pageTypeLabel : editableNote ? "Note" : "Raw"}</span>}
        </header>
        {isMarkdownFile && renameError && (
          <div className={styles["source-preview-document-controls"]}>
            <span role="alert">{renameError}</span>
          </div>
        )}
        {isMarkdownFile && isLoading && <p>문서를 불러오는 중입니다.</p>}
        {isMarkdownFile && errorMessage && <p>{errorMessage}</p>}
        {isMarkdownFile && !isLoading && !errorMessage && rawMarkdown !== null && editableNote && documentId && (
          <DynamicNoteEditor
            key={documentId}
            documentId={documentId}
            marker={editableNote.marker}
            initialBody={editableNote.body}
            initialVersion={noteContentVersion}
            sourceMode={sourceMode}
            onMarkdownEditContextChange={handleMarkdownEditContextChange}
            onSaveStatusChange={handleSaveStatusChange}
            onContentChanged={handleContentChanged}
          />
        )}
        {isPdfOrOther && isLoading && <p>문서를 불러오는 중입니다.</p>}
        {isPdfOrOther && errorMessage && <p>{errorMessage}</p>}
        {isPdfOrOther && !isLoading && !errorMessage && rawDocumentUrl && (
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
          <div className={styles["source-preview-meta"]}>
            <strong>원본 문서</strong>
            {sourceDocuments.map((document) => (
              <span key={document.id}>{document.filename || document.id}</span>
            ))}
          </div>
        )}
        {!pageId && !documentId && <p>연결된 Wiki page가 없는 항목입니다.</p>}
        </div>
      </div>
      {!fillMain && (
        <button
          type="button"
          className={styles["source-preview-resize-handle"]}
          aria-label="원본문서 패널 폭 조절"
          onPointerDown={onResizeStart}
        />
      )}
    </section>
  );
}
