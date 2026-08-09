import { MoreHorizontal } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { resolveEditorMode, useUserPreferences } from "@/entities/user";
import { MarkdownViewer } from "@/shared/ui/MarkdownViewer";
import { sideboxIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import { DynamicNoteEditor } from "@/features/note-editing/ui/DynamicNoteEditor";
import { HistoryPanel } from "@/features/document-history";
import { fetchDocumentOriginal, reflectDocumentToWiki } from "@/entities/document";
import { publishNotice } from "@/features/document-notifications";
import { fetchWikiPage } from "@/entities/wiki";
import { fetchNoteDraft } from "@/features/note-editing";
import { getErrorMessage } from "@/shared/lib/errors";
import { buildMarkdownDocumentFilename, getMarkdownDocumentTitle, splitEditableNoteMarkdown } from "@/entities/document/lib/note";
import { cx } from "@/shared/lib/classNames";
import styles from "./SourcePreviewPanel.module.css";
import type { ActiveMarkdownEditContext } from "@/features/agent-chat/lib/markdownEditContext";
import type { DocumentRole, SourceBlockHighlight } from "@/entities/document";
import type { NoteSaveStatus } from "@/entities/tree";
import type { WikiPageDetailResponse } from "@/entities/wiki";

// 다른 탭에서 편집한 내용을 반영하는 읽기 모드 주기 새로고침 간격
const READ_MODE_REFRESH_INTERVAL_MS = 10000;

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
  onRenameDocument,
  onRefreshDocuments,
  documentRole,
  parentLabel = "업로드 문서",
  editedAt = null,
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
  onRenameDocument?: (documentId: string, filename: string) => Promise<void>;
  /** 저장·위키 반영 직후 백엔드 문서 목록을 다시 받아 알림·트리를 갱신한다. */
  onRefreshDocuments?: () => void;
  /** 위키 반영 분기용 문서 역할. 문서 목록에서 아직 못 찾았으면 undefined. */
  documentRole?: DocumentRole;
  parentLabel?: string;
  editedAt?: string | null;
  isAgentPanelOpen: boolean;
  onOpenAgentPanel?: () => void;
  /** 홈에서 문서가 메인 영역을 채울 때: 고정폭/리사이즈 대신 남은 영역을 채운다 */
  fillMain?: boolean;
}) {
  const { preferences, preferencesReady, updatePreferences } = useUserPreferences();
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
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  // 버전 복원 후 문서 본문·버전을 다시 불러오기 위한 카운터
  const [documentReloadCount, setDocumentReloadCount] = useState(0);
  // 문서를 열면 읽기 모드로 시작하고, 편집 시작 버튼이나 본문 클릭으로 편집기로 전환한다.
  const [isEditingStarted, setIsEditingStarted] = useState(false);
  // Cmd/Ctrl+S로 저장할 때 저장 버튼에 눌림 효과를 잠깐 준다.
  const [isSaveKeyActive, setIsSaveKeyActive] = useState(false);
  const saveKeyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 편집기의 즉시 저장 함수 (저장 버튼에서 호출)
  const noteSaveRef = useRef<(() => Promise<boolean>) | null>(null);
  const registerNoteSave = useCallback((save: () => Promise<boolean>) => {
    noteSaveRef.current = save;
  }, []);
  const [noteSaveStatus, setNoteSaveStatus] = useState<NoteSaveStatus>("saved");
  const [noteSaveError, setNoteSaveError] = useState<string | null>(null);
  const blockRefs = useRef<Record<string, HTMLDivElement | null>>({});
  // 복원 완료 콜백이 도착한 시점에 보고 있는 문서를 판별하기 위한 ref
  const activeDocumentIdRef = useRef(documentId);
  activeDocumentIdRef.current = documentId;
  const optionsRef = useRef<HTMLDivElement | null>(null);
  const preferencesRef = useRef(preferences);
  preferencesRef.current = preferences;
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

  // 다른 문서로 이동하면 읽기 모드로 되돌린다.
  useEffect(() => {
    setIsEditingStarted(false);
  }, [documentId]);

  const startEditing = useCallback(() => {
    // 편집 직전에 최신 본문·버전을 다시 불러와 낡은 버전 기준 저장 충돌을 막는다.
    setDocumentReloadCount((count) => count + 1);
    setIsEditingStarted(true);
  }, []);

  // Cmd+S(macOS) / Ctrl+S(Windows·Linux)로 즉시 저장. 편집 모드는 유지하고 저장 버튼에 눌림 효과만 준다.
  useEffect(() => {
    if (!isEditingStarted) return;

    function handleSaveKey(event: KeyboardEvent) {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "s") return;
      event.preventDefault();
      void noteSaveRef.current?.();
      setIsSaveKeyActive(true);
      if (saveKeyTimerRef.current) clearTimeout(saveKeyTimerRef.current);
      saveKeyTimerRef.current = setTimeout(() => {
        saveKeyTimerRef.current = null;
        setIsSaveKeyActive(false);
      }, 200);
    }

    document.addEventListener("keydown", handleSaveKey);
    return () => {
      document.removeEventListener("keydown", handleSaveKey);
      if (saveKeyTimerRef.current) {
        clearTimeout(saveKeyTimerRef.current);
        saveKeyTimerRef.current = null;
      }
      setIsSaveKeyActive(false);
    };
  }, [isEditingStarted]);

  useEffect(() => {
    if (!preferencesReady) return;
    setSourceMode(resolveEditorMode(preferencesRef.current) === "markdown");
  }, [documentId, preferencesReady]);

  // 저장 상태는 문서가 바뀔 때만 되돌린다.
  // preferencesReady는 경로가 바뀔 때마다 다시 false→true가 되므로, 같이 묶으면 저장 중이거나 실패한 상태를 덮어쓴다.
  useEffect(() => {
    setIsOptionsOpen(false);
    setIsHistoryOpen(false);
    setNoteSaveStatus("saved");
    setNoteSaveError(null);
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

  const handleMarkdownEditContextChange = useCallback((context: ActiveMarkdownEditContext | null) => {
    onMarkdownEditContextChange?.(context);
  }, [onMarkdownEditContextChange]);

  const handleSaveStatusChange = useCallback((status: NoteSaveStatus, message: string | null) => {
    setNoteSaveStatus(status);
    setNoteSaveError(message);
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
        const draft = await fetchNoteDraft(documentId);
        if (draft) {
          if (!ignore) {
            setRawMarkdown(draft.markdown);
            setNoteContentVersion(draft.content_version);
          }
          return;
        }
        const blob = await fetchDocumentOriginal(documentId);
        const text = await blob.text();
        if (!ignore) {
          setRawMarkdown(text);
          setNoteContentVersion(0);
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
  }, [documentId, documentReloadCount, isMarkdownFile, pageId]);

  // 다른 탭에서 같은 문서를 편집할 수 있으므로 읽기 모드에서 주기적으로 백엔드 본문을 새로고침한다.
  // 편집 모드에서는 작성 중인 내용을 덮어쓰지 않도록 새로고침하지 않는다
  // (편집 진입 시 최신본 재로드 + 저장 시 content_version 충돌 감지가 있다).
  useEffect(() => {
    if (!isMarkdownFile || !documentId || isEditingStarted) return;

    const intervalId = window.setInterval(() => {
      fetchNoteDraft(documentId)
        .then((draft) => {
          // 응답 도착 전에 다른 문서로 이동했거나 편집을 시작했으면 무시한다.
          if (!draft || documentId !== activeDocumentIdRef.current) return;
          setRawMarkdown((current) => (current === draft.markdown ? current : draft.markdown));
          setNoteContentVersion(draft.content_version);
        })
        .catch(() => {
          // 주기 새로고침 실패는 조용히 넘기고 다음 주기에 재시도한다.
        });
    }, READ_MODE_REFRESH_INTERVAL_MS);

    return () => window.clearInterval(intervalId);
  }, [documentId, isEditingStarted, isMarkdownFile]);

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
          <span>{parentLabel}</span>
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
          {isMarkdownFile && (
            isEditingStarted ? (
              <button
                type="button"
                className={cx(styles["source-preview-edit-start"], isSaveKeyActive && styles["is-pressed"])}
                onClick={() => {
                  void (async () => {
                    // 저장이 실제로 성공했을 때만 읽기 모드로 돌아간다 (실패 시 편집 내용 보존)
                    const saved = await noteSaveRef.current?.();
                    if (saved === false) return;
                    noteSaveRef.current = null;
                    // 에디터가 언마운트되면 "saved" 상태 보고가 끊기므로 여기서 직접 리셋한다
                    setNoteSaveStatus("saved");
                    setNoteSaveError(null);
                    setIsEditingStarted(false);
                    setDocumentReloadCount((count) => count + 1);
                    // 저장으로 백엔드 needs_reingest가 켜진다. 처리 중 문서가 없으면 목록 폴링이 꺼져 있어
                    // 여기서 직접 다시 받아야 재분석 제안 알림이 저장 직후 뜬다.
                    onRefreshDocuments?.();
                  })();
                }}
              >
                저장
              </button>
            ) : (
              <button
                type="button"
                className={styles["source-preview-edit-start"]}
                onClick={startEditing}
              >
                편집 시작
              </button>
            )
          )}
          <div className={styles["source-preview-tab"]}>
          {!isAgentPanelOpen && (
            <button type="button" aria-label="AI 사이드바 열기" onClick={onOpenAgentPanel}>
              <SvgIcon src={sideboxIcon} className={styles["source-preview-tab-icon"]} />
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
            {isOptionsOpen && !pageId && documentId && (
              <div className={styles["source-preview-options-menu"]}>
                <button
                  type="button"
                  onClick={() => {
                    setIsOptionsOpen(false);
                    reflectDocumentToWiki(documentId, documentRole)
                      .then(() => {
                        // 원본 문서면 변환 결과가 새 markdown 문서로 생기므로 목록을 다시 받는다
                        onRefreshDocuments?.();
                        publishNotice({
                          kind: "completed",
                          title: "위키 반영 요청",
                          message: `"${visibleTitle}" 문서 처리를 시작했습니다.`
                        });
                      })
                      .catch((error: unknown) => {
                        publishNotice({
                          kind: "failed",
                          title: "위키 반영 실패",
                          message: getErrorMessage(error, "위키 반영 요청에 실패했습니다.")
                        });
                      });
                  }}
                >
                  위키에 반영
                </button>
                {isMarkdownFile && (
                  <>
                    <button
                      type="button"
                      onClick={() => {
                        const nextMode = sourceMode ? "wysiwyg" : "markdown";
                        setSourceMode(nextMode === "markdown");
                        updatePreferences((current) => ({
                          ...current,
                          editor: { ...current.editor, lastMode: nextMode }
                        }));
                        setIsOptionsOpen(false);
                      }}
                    >
                      {sourceMode ? "자동 미리보기로 전환" : "Markdown 원문 보기"}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setIsHistoryOpen(true);
                        setIsOptionsOpen(false);
                      }}
                    >
                      버전 기록
                    </button>
                  </>
                )}
              </div>
            )}
          </div>
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
              readOnly={!isEditingStarted}
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
          isEditingStarted ? (
            <DynamicNoteEditor
              key={`${documentId}:${documentReloadCount}`}
              documentId={documentId}
              marker={editableNote.marker}
              initialBody={editableNote.body}
              initialVersion={noteContentVersion}
              sourceMode={sourceMode}
              onMarkdownEditContextChange={handleMarkdownEditContextChange}
              onSaveStatusChange={handleSaveStatusChange}
              onRegisterSave={registerNoteSave}
            />
          ) : (
            // 읽기 모드 본문을 클릭하면 바로 편집을 시작한다 (링크 클릭은 제외)
            <div
              className={styles["source-preview-read-surface"]}
              onClick={(event) => {
                if ((event.target as HTMLElement).closest("a")) return;
                startEditing();
              }}
            >
              <MarkdownViewer markdown={editableNote.body} />
            </div>
          )
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
      {isHistoryOpen && isMarkdownFile && documentId && (
        <HistoryPanel
          documentId={documentId}
          onRestored={(restoredDocumentId) => {
            // 복원 중 다른 문서로 전환했으면 무시한다. 현재 문서의 에디터가 리마운트되어
            // 저장 전 편집분이 초기화되는 것을 막는다.
            if (restoredDocumentId === activeDocumentIdRef.current) {
              setDocumentReloadCount((count) => count + 1);
            }
          }}
          onClose={() => setIsHistoryOpen(false)}
        />
      )}
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
