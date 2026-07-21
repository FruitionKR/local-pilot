"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
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
  const { status, errorMessage, queueSave } = useNoteAutosave({ documentId, marker, initialVersion });
  const editorExtensions = useMemo(() => [markdown(), EditorView.lineWrapping], []);
  const publishMarkdownEditContext = useCallback((markdownValue: string, from: number, to: number) => {
    onMarkdownEditContextChange?.({
      documentId,
      editorSnapshot: buildMarkdownEditorSnapshot(markdownValue, from, to)
    });
  }, [documentId, onMarkdownEditContextChange]);

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
            publishMarkdownEditContext(view.state.doc.toString(), selection.from, selection.to);
          }}
          onUpdate={(viewUpdate) => {
            if (!viewUpdate.docChanged && !viewUpdate.selectionSet) return;
            const selection = viewUpdate.state.selection.main;
            publishMarkdownEditContext(viewUpdate.state.doc.toString(), selection.from, selection.to);
          }}
          onChange={(nextBody) => {
            setBody(nextBody);
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
