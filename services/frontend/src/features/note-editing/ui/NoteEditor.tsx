"use client";

import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame-dark.css";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { markdown } from "@codemirror/lang-markdown";
import { history } from "@codemirror/commands";
import { EditorView } from "@codemirror/view";
import { Crepe, CrepeFeature } from "@milkdown/crepe";
import { editorViewCtx, keymapCtx, parserCtx } from "@milkdown/core";
import type { KeymapItem } from "@milkdown/core";
import { closeHistory, history as prosemirrorHistory } from "@milkdown/prose/history";
import { listItemSchema } from "@milkdown/kit/preset/commonmark";
import { Slice } from "@milkdown/prose/model";
import { liftListItem } from "@milkdown/prose/schema-list";
import { TextSelection } from "@milkdown/prose/state";
import { useUserPreferences } from "@/entities/user";
import { buildMarkdownEditorSnapshot } from "@/features/agent-chat/lib/markdownEditContext";
import type { ActiveMarkdownEditContext } from "@/features/agent-chat/lib/markdownEditContext";
import type { NoteSaveStatus } from "@/entities/tree/model/tree";
import { useNoteAutosave, type DetachedNoteSaveResult } from "../model/useNoteAutosave";
import styles from "./NoteEditor.module.css";

/** Backspace로 리스트 항목의 첫 문단 맨 앞을 지우면 문단을 리스트 밖으로 빼낸다 (Shift+Tab과 동일).
 *  commonmark 기본 동작은 joinBackward라 문단이 list_item 안에 남아,
 *  '-'를 지운 뒤에도 캐럿이 들여쓰기된 자리에 계속 머무른다. */
const liftListItemOnBackspace: KeymapItem["onRun"] = (ctx) => (state, dispatch, view) => {
  const { selection } = state;
  if (!(selection instanceof TextSelection) || !selection.empty) return false;
  const { $from } = selection;
  // 리스트 항목의 첫 블록 맨 앞일 때만 처리하고, 나머지는 기본 Backspace에 넘긴다
  if ($from.parentOffset !== 0 || $from.index(-1) !== 0) return false;
  const listItemType = listItemSchema.type(ctx);
  if ($from.node(-1).type !== listItemType) return false;
  return liftListItem(listItemType)(state, dispatch, view);
};

export function NoteEditor({
  documentId,
  marker,
  initialBody,
  initialVersion,
  sourceMode,
  onMarkdownEditContextChange,
  onSaveStatusChange,
  onDetachedSaveComplete,
  onRegisterSave
}: {
  documentId: string;
  marker: string;
  initialBody: string;
  initialVersion: number;
  sourceMode: boolean;
  onMarkdownEditContextChange?: (context: ActiveMarkdownEditContext | null) => void;
  onSaveStatusChange?: (status: NoteSaveStatus, errorMessage: string | null) => void;
  onDetachedSaveComplete?: (result: DetachedNoteSaveResult) => void;
  /** 부모가 Cmd/Ctrl+S로 즉시 저장할 수 있게 저장 함수를 등록한다. */
  onRegisterSave?: (save: () => Promise<boolean>) => void;
}) {
  const { preferences } = useUserPreferences();
  const [body, setBody] = useState(initialBody);
  const { status, errorMessage, contentVersion, queueSave, saveNow } = useNoteAutosave({
    documentId,
    marker,
    initialVersion,
    onDetachedSaveComplete
  });
  const editorExtensions = useMemo(
    () => [
      markdown(),
      // 입력 하나 단위로 undo되도록 그룹 병합을 끈다 (기본 500ms 내 입력이 한 그룹으로 묶임)
      history({ newGroupDelay: 0 }),
      ...(preferences.editor.markdown.lineWrapping ? [EditorView.lineWrapping] : [])
    ],
    [preferences.editor.markdown.lineWrapping]
  );
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
  const programmaticBodyRef = useRef<string | null>(null);
  const saveNowRef = useRef(saveNow);
  queueSaveRef.current = queueSave;
  saveNowRef.current = saveNow;

  useEffect(() => {
    onRegisterSave?.(() => saveNowRef.current(bodyRef.current));
  }, [onRegisterSave]);

  const applyMarkdown = useCallback((expectedMarkdown: string, nextMarkdown: string, applyOperationId: string) => {
    if (bodyRef.current !== expectedMarkdown) return false;
    bodyRef.current = nextMarkdown;
    programmaticBodyRef.current = nextMarkdown;
    setBody(nextMarkdown);
    // 전체 문서 교체를 undo 히스토리에서 제외한다.
    // (기본 replaceAll은 통째 트랜잭션이라 cmd+z 한 번에 문서 전체가 되돌려짐)
    crepeRef.current?.editor.action((ctx) => {
      const view = ctx.get(editorViewCtx);
      const parser = ctx.get(parserCtx);
      const doc = parser(nextMarkdown);
      if (!doc) return;
      const { state } = view;
      const tr = state.tr.replace(0, state.doc.content.size, new Slice(doc.content, 0, 0));
      tr.setMeta("addToHistory", false);
      view.dispatch(tr);
    });
    queueSaveRef.current(nextMarkdown, "agent", applyOperationId);
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

  // 표 행/열 추가 핸들을 오른쪽·아래쪽 바깥 경계에서만 노출한다.
  // (위젯이 placement를 DOM에 남기지 않아 좌표로 판별한다)
  useEffect(() => {
    if (sourceMode || !wysiwygRootRef.current) return;
    const root = wysiwygRootRef.current;
    const EDGE_TOLERANCE_PX = 8;

    const observer = new MutationObserver(() => {
      root.querySelectorAll<HTMLElement>(".milkdown-table-block").forEach((block) => {
        const table = block.querySelector<HTMLElement>("table.children");
        if (!table) return;
        const tableRect = table.getBoundingClientRect();

        const yHandle = block.querySelector<HTMLElement>('[data-role="y-line-drag-handle"]');
        if (yHandle?.dataset.show === "true"
          && yHandle.getBoundingClientRect().left < tableRect.right - EDGE_TOLERANCE_PX) {
          yHandle.dataset.show = "false";
        }

        const xHandle = block.querySelector<HTMLElement>('[data-role="x-line-drag-handle"]');
        if (xHandle?.dataset.show === "true"
          && xHandle.getBoundingClientRect().top < tableRect.bottom - EDGE_TOLERANCE_PX) {
          xHandle.dataset.show = "false";
        }
      });
    });
    observer.observe(root, { subtree: true, attributes: true, attributeFilter: ["data-show", "style"] });
    return () => observer.disconnect();
  }, [documentId, sourceMode]);

  // 선택 툴바 버튼에 hover 설명(0.5초 뒤 표시되는 커스텀 tooltip)을 붙인다.
  // (Crepe가 버튼에 라벨·식별 속성을 넣지 않아 렌더 순서로 매핑한다: 볼드→기울임→취소선→코드→[수식]→링크)
  useEffect(() => {
    if (sourceMode || !wysiwygRootRef.current) return;
    const root = wysiwygRootRef.current;
    const LABELS_WITH_LATEX = ["볼드", "기울임꼴", "취소선", "인라인 코드", "수식", "링크"];
    const LABELS_WITHOUT_LATEX = ["볼드", "기울임꼴", "취소선", "인라인 코드", "링크"];

    const observer = new MutationObserver(() => {
      root.querySelectorAll<HTMLElement>(".milkdown-toolbar").forEach((toolbar) => {
        const items = toolbar.querySelectorAll<HTMLButtonElement>(".toolbar-item");
        const labels = items.length === LABELS_WITH_LATEX.length ? LABELS_WITH_LATEX : LABELS_WITHOUT_LATEX;
        items.forEach((item, index) => {
          const label = labels[index];
          if (!label || item.dataset.tooltip === label) return;
          item.dataset.tooltip = label;
          item.setAttribute("aria-label", label);
        });
      });
    });
    observer.observe(root, { subtree: true, childList: true });
    return () => observer.disconnect();
  }, [documentId, sourceMode]);

  // '/' 슬래시 메뉴가 화면 밖으로 넘어가지 않게 표시 위치를 viewport 안으로 보정한다.
  // (Crepe는 flip만 적용하고 shift 미들웨어를 노출하지 않아 가장자리에서 잘림)
  useEffect(() => {
    if (sourceMode || !wysiwygRootRef.current) return;
    const root = wysiwygRootRef.current;
    const VIEWPORT_MARGIN_PX = 8;

    const observer = new MutationObserver(() => {
      const menu = root.querySelector<HTMLElement>(".milkdown-slash-menu");
      if (!menu || menu.dataset.show !== "true") return;
      const rect = menu.getBoundingClientRect();
      let deltaX = 0;
      let deltaY = 0;
      if (rect.right > window.innerWidth - VIEWPORT_MARGIN_PX) {
        deltaX = window.innerWidth - VIEWPORT_MARGIN_PX - rect.right;
      }
      if (rect.left + deltaX < VIEWPORT_MARGIN_PX) deltaX = VIEWPORT_MARGIN_PX - rect.left;
      if (rect.bottom > window.innerHeight - VIEWPORT_MARGIN_PX) {
        deltaY = window.innerHeight - VIEWPORT_MARGIN_PX - rect.bottom;
      }
      if (rect.top + deltaY < VIEWPORT_MARGIN_PX) deltaY = VIEWPORT_MARGIN_PX - rect.top;
      if (deltaX === 0 && deltaY === 0) return;
      menu.style.left = `${parseFloat(menu.style.left || "0") + deltaX}px`;
      menu.style.top = `${parseFloat(menu.style.top || "0") + deltaY}px`;
    });
    observer.observe(root, { subtree: true, attributes: true, attributeFilter: ["data-show", "style"] });
    return () => observer.disconnect();
  }, [documentId, sourceMode]);

  useEffect(() => {
    if (sourceMode || !wysiwygRootRef.current) return;

    let isDisposed = false;
    // Crepe의 create/destroy가 비동기라 root를 공유하면 이전 인스턴스 DOM이 남은 채
    // 다음 인스턴스가 붙어 편집기 높이가 잠깐 두 배가 된다(문서 전환·StrictMode 재실행).
    // 인스턴스마다 전용 host를 두고 정리 때 동기로 떼어내 겹침을 없앤다.
    const host = document.createElement("div");
    wysiwygRootRef.current.appendChild(host);

    const crepe = new Crepe({
      root: host,
      defaultValue: bodyRef.current,
      features: {
        [CrepeFeature.AI]: false,
        [CrepeFeature.ImageBlock]: false,
        [CrepeFeature.TopBar]: false
      },
      featureConfigs: {
        // '/' 슬래시 메뉴 한글화
        [CrepeFeature.BlockEdit]: {
          textGroup: {
            label: "텍스트",
            text: { label: "본문" },
            h1: { label: "제목 1" },
            h2: { label: "제목 2" },
            h3: { label: "제목 3" },
            h4: { label: "제목 4" },
            h5: { label: "제목 5" },
            h6: { label: "제목 6" },
            quote: { label: "인용" },
            divider: { label: "구분선" }
          },
          listGroup: {
            label: "목록",
            bulletList: { label: "글머리 기호 목록" },
            orderedList: { label: "번호 목록" },
            taskList: { label: "체크리스트" }
          },
          advancedGroup: {
            label: "고급",
            image: { label: "이미지" },
            codeBlock: { label: "코드 블록" },
            table: { label: "표" },
            math: { label: "수식" }
          }
        },
        [CrepeFeature.Placeholder]: {
          text: "내용을 입력하거나 '/'로 명령을 여세요"
        }
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
        queueSaveRef.current(nextBody);
      });
    });
    crepe.editor.config((ctx) => {
      // commonmark 기본 Backspace(priority 50)보다 먼저 실행시킨다
      ctx.get(keymapCtx).add({ key: "Backspace", priority: 100, onRun: liftListItemOnBackspace });
    });
    crepeRef.current = crepe;
    const ready = crepe.create();
    // 입력 하나가 되돌리기 한 단계가 되도록 history의 그룹 병합을 사실상 끈다.
    // editor.config로 historyProviderConfig를 넣으면 plugin이 만들어질 때 이미 읽힌 뒤라
    // 반영되지 않으므로, 생성이 끝난 뒤 ProseMirror state에서 plugin을 교체한다.
    // newGroupDelay는 0을 쓸 수 없다 — prosemirror-history가 `config.newGroupDelay || 500`으로
    // 읽어 falsy인 0을 기본값 500으로 되돌린다. 1이 실질적인 최소값이다.
    void ready.then(() => {
      if (isDisposed) return;
      crepe.editor.action((ctx) => {
        const view = ctx.get(editorViewCtx);
        const plugins = view.state.plugins.map((plugin) =>
          (plugin as unknown as { key?: string }).key?.startsWith("history$")
            ? prosemirrorHistory({ newGroupDelay: 1 })
            : plugin
        );
        view.updateState(view.state.reconfigure({ plugins }));
        // 변경마다 되돌리기 그룹을 끊어 입력 하나가 한 단계가 되게 한다.
        // 단, IME 조합 중에는 끊지 않는다. 조합 중간으로 되감으면 문서와 OS IME의 조합 버퍼가
        // 어긋나 IME가 남은 자모를 다시 합성해 버린다('되'로 되돌렸는데 화면엔 '된'이 되는 증상).
        const original = view.dispatch.bind(view);
        view.dispatch = (tr) => {
          if (tr.docChanged && !view.composing) closeHistory(tr);
          original(tr);
        };
      });
    });

    return () => {
      isDisposed = true;
      if (crepeRef.current === crepe) crepeRef.current = null;
      // DOM은 먼저 떼어 화면에서 즉시 사라지게 하고, 내부 정리는 create가 끝난 뒤에 한다.
      host.remove();
      void ready.catch(() => {}).then(() => crepe.destroy());
    };
  }, [documentId, sourceMode]);

  return (
    <div className={styles["note-editor-shell"]}>
      {sourceMode ? (
        <CodeMirror
          className={styles["note-markdown-editor"]}
          value={body}
          minHeight="420px"
          extensions={editorExtensions}
          basicSetup={{
            lineNumbers: preferences.editor.markdown.lineNumbers,
            // 기본 history 대신 위 editorExtensions의 history({ newGroupDelay: 0 })를 쓴다
            history: false,
            foldGutter: false,
            highlightActiveLine: preferences.editor.markdown.highlightActiveLine,
            highlightActiveLineGutter: preferences.editor.markdown.lineNumbers
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
        <div ref={wysiwygRootRef} className={styles["note-wysiwyg-editor"]} />
      )}
    </div>
  );
}
