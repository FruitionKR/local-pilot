"use client";

import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame-dark.css";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { markdown } from "@codemirror/lang-markdown";
import { EditorView } from "@codemirror/view";
import { Crepe, CrepeFeature } from "@milkdown/crepe";
import { replaceAll } from "@milkdown/utils";
import { buildMarkdownEditorSnapshot } from "../../_lib/markdownEditContext";
import type { ActiveMarkdownEditContext } from "../../_lib/markdownEditContext";
import type { NoteSaveStatus } from "../../_lib/types";
import { useNoteAutosave } from "./useNoteAutosave";

export function NoteEditor({
  documentId,
  marker,
  initialBody,
  initialVersion,
  sourceMode,
  onMarkdownEditContextChange,
  onSaveStatusChange,
  onContentChanged
}: {
  documentId: string;
  marker: string;
  initialBody: string;
  initialVersion: number;
  sourceMode: boolean;
  onMarkdownEditContextChange?: (context: ActiveMarkdownEditContext | null) => void;
  onSaveStatusChange?: (status: NoteSaveStatus, errorMessage: string | null) => void;
  onContentChanged?: (markdown: string) => void;
}) {
  const [body, setBody] = useState(initialBody);
  const { status, errorMessage, contentVersion, queueSave } = useNoteAutosave({ documentId, marker, initialVersion });
  const editorExtensions = useMemo(() => [markdown(), EditorView.lineWrapping], []);
  const wysiwygRootRef = useRef<HTMLDivElement | null>(null);
  const crepeRef = useRef<Crepe | null>(null);
  const selectionRef = useRef({ from: 0, to: 0 });
  const bodyRef = useRef(body);
  const queueSaveRef = useRef(queueSave);
  const publishMarkdownEditContextRef = useRef<(
    markdownValue: string,
    from?: number,
    to?: number,
    wholeDocument?: boolean
  ) => void>(() => {});
  const onContentChangedRef = useRef(onContentChanged);
  const programmaticBodyRef = useRef<string | null>(null);
  queueSaveRef.current = queueSave;
  onContentChangedRef.current = onContentChanged;

  const applyMarkdown = useCallback((expectedMarkdown: string, nextMarkdown: string) => {
    if (bodyRef.current !== expectedMarkdown) return false;
    bodyRef.current = nextMarkdown;
    programmaticBodyRef.current = nextMarkdown;
    setBody(nextMarkdown);
    crepeRef.current?.editor.action(replaceAll(nextMarkdown));
    onContentChangedRef.current?.(nextMarkdown);
    queueSaveRef.current(nextMarkdown);
    return true;
  }, []);

  const publishMarkdownEditContext = useCallback((
    markdownValue: string,
    from = 0,
    to = 0,
    wholeDocument = false
  ) => {
    onMarkdownEditContextChange?.({
      documentId,
      baseVersion: contentVersion,
      editorSnapshot: wholeDocument
        ? {
            markdown: markdownValue,
            target: {
              type: "whole_document",
              startLine: 1,
              endLine: markdownValue.split("\n").length
            }
          }
        : buildMarkdownEditorSnapshot(markdownValue, from, to),
      applyMarkdown
    });
  }, [applyMarkdown, contentVersion, documentId, onMarkdownEditContextChange]);
  publishMarkdownEditContextRef.current = publishMarkdownEditContext;

  useEffect(() => {
    onSaveStatusChange?.(status, errorMessage);
  }, [errorMessage, onSaveStatusChange, status]);

  useEffect(() => {
    publishMarkdownEditContext(
      bodyRef.current,
      selectionRef.current.from,
      selectionRef.current.to,
      !sourceMode
    );
  }, [publishMarkdownEditContext, sourceMode]);

  useEffect(() => () => onMarkdownEditContextChange?.(null), [onMarkdownEditContextChange]);

  useEffect(() => {
    if (sourceMode || !wysiwygRootRef.current) return;

    let isDisposed = false;
    const crepe = new Crepe({
      root: wysiwygRootRef.current,
      defaultValue: bodyRef.current,
      features: {
        [CrepeFeature.AI]: false,
        [CrepeFeature.ImageBlock]: false,
        [CrepeFeature.TopBar]: false
      }
    }).on((listener) => {
      listener.markdownUpdated((_ctx, nextBody, previousBody) => {
        if (isDisposed || nextBody === previousBody) return;
        bodyRef.current = nextBody;
        setBody(nextBody);
        publishMarkdownEditContextRef.current(nextBody, 0, 0, true);
        if (programmaticBodyRef.current === nextBody) {
          programmaticBodyRef.current = null;
          return;
        }
        programmaticBodyRef.current = null;
        onContentChangedRef.current?.(nextBody);
        queueSaveRef.current(nextBody);
      });
    });
    crepeRef.current = crepe;
    void crepe.create();

    return () => {
      isDisposed = true;
      if (crepeRef.current === crepe) crepeRef.current = null;
      void crepe.destroy();
    };
  }, [documentId, sourceMode]);

  return (
    <div className="note-editor-shell">
      {sourceMode ? (
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
            onContentChanged?.(nextBody);
            queueSave(nextBody);
          }}
        />
      ) : (
        <div ref={wysiwygRootRef} className="note-wysiwyg-editor" />
      )}
    </div>
  );
}
