"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { markdown } from "@codemirror/lang-markdown";
import { EditorView } from "@codemirror/view";
import { MarkdownViewer } from "../MarkdownViewer";
import { buildMarkdownEditorSnapshot } from "../../_lib/markdownEditContext";
import type { ActiveMarkdownEditContext } from "../../_lib/markdownEditContext";
import { useNoteAutosave, type NoteSaveStatus } from "./useNoteAutosave";

const STATUS_LABELS: Record<NoteSaveStatus, string> = {
  saved: "저장됨",
  dirty: "편집됨",
  saving: "저장 중",
  error: "저장 실패",
  conflict: "충돌 · 다시 열어주세요"
};

export function NoteEditor({
  documentId,
  marker,
  initialBody,
  initialVersion,
  onMarkdownEditContextChange
}: {
  documentId: string;
  marker: string;
  initialBody: string;
  initialVersion: number;
  onMarkdownEditContextChange?: (context: ActiveMarkdownEditContext | null) => void;
}) {
  const [body, setBody] = useState(initialBody);
  const [mode, setMode] = useState<"edit" | "preview">("edit");
  const { status, errorMessage, contentVersion, queueSave } = useNoteAutosave({ documentId, marker, initialVersion });
  const editorExtensions = useMemo(() => [markdown(), EditorView.lineWrapping], []);
  const selectionRef = useRef({ from: 0, to: 0 });
  const bodyRef = useRef(body);
  const queueSaveRef = useRef(queueSave);
  const programmaticBodyRef = useRef<string | null>(null);
  queueSaveRef.current = queueSave;
  const applyMarkdown = useCallback((expectedMarkdown: string, nextMarkdown: string) => {
    if (bodyRef.current !== expectedMarkdown) return false;
    bodyRef.current = nextMarkdown;
    programmaticBodyRef.current = nextMarkdown;
    setBody(nextMarkdown);
    setMode("edit");
    queueSaveRef.current(nextMarkdown);
    return true;
  }, []);
  const publishMarkdownEditContext = useCallback((markdownValue: string, from: number, to: number) => {
    onMarkdownEditContextChange?.({
      documentId,
      baseVersion: contentVersion,
      editorSnapshot: buildMarkdownEditorSnapshot(markdownValue, from, to),
      applyMarkdown
    });
  }, [applyMarkdown, contentVersion, documentId, onMarkdownEditContextChange]);

  // baseVersion(contentVersion)이 바뀔 때만 컨텍스트를 재발행한다.
  // 키 입력·selection 변경은 onUpdate에서만 발행해 매 입력 상위 리렌더를 피한다.
  useEffect(() => {
    publishMarkdownEditContext(bodyRef.current, selectionRef.current.from, selectionRef.current.to);
  }, [publishMarkdownEditContext]);

  useEffect(() => () => onMarkdownEditContextChange?.(null), [onMarkdownEditContextChange]);

  return (
    <div className="note-editor-shell">
      <div className="note-editor-toolbar">
        <div className="note-editor-modes" aria-label="노트 보기 방식">
          <button
            type="button"
            className={mode === "edit" ? "is-active" : undefined}
            aria-pressed={mode === "edit"}
            onClick={() => setMode("edit")}
          >
            편집
          </button>
          <button
            type="button"
            className={mode === "preview" ? "is-active" : undefined}
            aria-pressed={mode === "preview"}
            onClick={() => setMode("preview")}
          >
            미리보기
          </button>
        </div>
        <div
          className={`note-save-status is-${status}`}
          role={status === "error" || status === "conflict" ? "alert" : "status"}
          title={errorMessage ?? undefined}
        >
          {STATUS_LABELS[status]}
        </div>
      </div>
      {mode === "edit" ? (
        <CodeMirror
          className="note-markdown-editor"
          value={body}
          minHeight="420px"
          extensions={editorExtensions}
          basicSetup={{
            lineNumbers: false,
            foldGutter: false,
            highlightActiveLine: false,
            highlightActiveLineGutter: false
          }}
          onCreateEditor={(view) => {
            const selection = view.state.selection.main;
            selectionRef.current = { from: selection.from, to: selection.to };
            publishMarkdownEditContext(view.state.doc.toString(), selection.from, selection.to);
          }}
          onUpdate={(viewUpdate) => {
            if (!viewUpdate.docChanged && !viewUpdate.selectionSet) return;
            const selection = viewUpdate.state.selection.main;
            selectionRef.current = { from: selection.from, to: selection.to };
            publishMarkdownEditContext(viewUpdate.state.doc.toString(), selection.from, selection.to);
          }}
          onChange={(nextBody) => {
            bodyRef.current = nextBody;
            setBody(nextBody);
            if (programmaticBodyRef.current === nextBody) {
              programmaticBodyRef.current = null;
              return;
            }
            programmaticBodyRef.current = null;
            queueSave(nextBody);
          }}
        />
      ) : (
        <div className="note-markdown-preview">
          <MarkdownViewer markdown={body} />
        </div>
      )}
    </div>
  );
}
