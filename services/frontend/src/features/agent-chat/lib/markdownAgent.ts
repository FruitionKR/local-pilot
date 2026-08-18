import type { ActiveMarkdownEditContext, MarkdownEditorSnapshot } from "./markdownEditContext";
import type { AiModelSelection } from "@/entities/ai";

export type AgentTurnRequest = {
  session_id: string;
  documentId: string;
  baseVersion: number;
  message: string;
  provider: string;
  model: string;
  allow_web_search: boolean;
  conversationContext?: { selected_pair_ids: string[] };
  editorSnapshot: MarkdownEditorSnapshot;
};

export type AgentTurnRequestContext = {
  sessionId: string;
  selectedModel: AiModelSelection;
  selectedPairIds: string[];
};

export type AgentTurnAction =
  | "chat_answer"
  | "conversation_reply"
  | "markdown_edit"
  | "markdown_create"
  | "clarify"
  | "reject"
  | "folder_organize"
  | "workspace_workflow"
  | "skill_authoring"
  | "skill_draft_proposal";

export type DocumentCommandAction = Exclude<AgentTurnAction, "chat_answer" | "conversation_reply" | "clarify" | "reject">;

export type AgentTurnEdit = {
  operation: "replace" | "insert_after";
  requested_target: {
    type: "selection" | "current_section" | "whole_document";
    start_line: number;
    end_line: number;
  };
  actual_target: {
    type: "selection" | "current_section" | "whole_document";
    start_line: number;
    end_line: number;
  };
  scope_expanded: boolean;
  changed: boolean;
  summary: string;
  replacement_markdown: string;
};

export type AgentTurnResult = {
  action: AgentTurnAction;
  route: {
    action: AgentTurnAction;
    confidence: number;
    reason: string;
    edit_goal: string | null;
  };
  message: string | null;
  chat: { answer: string } | null;
  edit: AgentTurnEdit | null;
  generated_markdown: {
    title: string;
    summary: string;
    markdown: string;
  } | null;
};

export type GeneratedMarkdownDraft = NonNullable<AgentTurnResult["generated_markdown"]>;

export type AgentTurnResponse = {
  documentId: string;
  baseVersion: number;
  requestId: string;
  apply_operation_id: string;
  status: "completed";
  result: AgentTurnResult;
};

export type MarkdownDiffLine = {
  type: "context" | "delete" | "insert";
  text: string;
};

export type MarkdownEditPreview = {
  operation: AgentTurnEdit["operation"];
  summary: string;
  replacementMarkdown: string;
  nextMarkdown: string;
  diffLines: MarkdownDiffLine[];
};

export type ChatTurnPresentation =
  | { kind: "query"; grounded: boolean }
  | { kind: "document-command"; action: DocumentCommandAction };

const MAX_LCS_CELLS = 250_000;

export function isDocumentCommandAction(action: string | undefined): action is DocumentCommandAction {
  return action === "markdown_edit"
    || action === "markdown_create"
    || action === "folder_organize"
    || action === "workspace_workflow"
    || action === "skill_authoring"
    || action === "skill_draft_proposal";
}

/** 채팅 결과 UI는 Query와 문서 명령 두 표현만 사용한다. */
export function resolveChatTurnPresentation(action: string | undefined): ChatTurnPresentation {
  if (isDocumentCommandAction(action)) return { kind: "document-command", action };
  return { kind: "query", grounded: !action || action === "chat_answer" };
}

export function buildAgentTurnRequest(
  message: string,
  context: ActiveMarkdownEditContext,
  requestContext: AgentTurnRequestContext
): AgentTurnRequest {
  return {
    session_id: requestContext.sessionId,
    documentId: context.documentId,
    baseVersion: context.baseVersion,
    message,
    provider: requestContext.selectedModel.provider,
    model: requestContext.selectedModel.model,
    allow_web_search: false,
    // 선택한 문답이 있을 때만 서버가 지원하는 pair ID 계약으로 전달한다.
    ...(requestContext.selectedPairIds.length > 0
      ? { conversationContext: { selected_pair_ids: requestContext.selectedPairIds } }
      : {}),
    editorSnapshot: context.editorSnapshot
  };
}

export function buildGeneratedMarkdownFilename(title: string): string {
  const safeTitle = title
    .trim()
    .replace(/[\\/:*?"<>|]/g, "-")
    .replace(/\s*-\s*/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-+|-+$/g, "")
    .replace(/\s+/g, " ")
    .slice(0, 80) || "AI 문서";
  return safeTitle.toLowerCase().endsWith(".md") ? safeTitle : `${safeTitle}.md`;
}

function targetsMatch(request: AgentTurnRequest, edit: AgentTurnEdit): boolean {
  const requestTarget = request.editorSnapshot.target;
  return requestTarget.type === edit.requested_target.type
    && requestTarget.startLine === edit.requested_target.start_line
    && requestTarget.endLine === edit.requested_target.end_line;
}

function lineDiff(before: string, after: string): MarkdownDiffLine[] {
  const beforeLines = before.split("\n");
  const afterLines = after.split("\n");
  if (beforeLines.length * afterLines.length > MAX_LCS_CELLS) {
    return [
      ...beforeLines.map((text) => ({ type: "delete" as const, text })),
      ...afterLines.map((text) => ({ type: "insert" as const, text }))
    ];
  }
  const lengths = Array.from(
    { length: beforeLines.length + 1 },
    () => Array<number>(afterLines.length + 1).fill(0)
  );

  for (let beforeIndex = beforeLines.length - 1; beforeIndex >= 0; beforeIndex -= 1) {
    for (let afterIndex = afterLines.length - 1; afterIndex >= 0; afterIndex -= 1) {
      lengths[beforeIndex][afterIndex] = beforeLines[beforeIndex] === afterLines[afterIndex]
        ? lengths[beforeIndex + 1][afterIndex + 1] + 1
        : Math.max(lengths[beforeIndex + 1][afterIndex], lengths[beforeIndex][afterIndex + 1]);
    }
  }

  const diff: MarkdownDiffLine[] = [];
  let beforeIndex = 0;
  let afterIndex = 0;
  while (beforeIndex < beforeLines.length && afterIndex < afterLines.length) {
    if (beforeLines[beforeIndex] === afterLines[afterIndex]) {
      diff.push({ type: "context", text: beforeLines[beforeIndex] });
      beforeIndex += 1;
      afterIndex += 1;
    } else if (lengths[beforeIndex + 1][afterIndex] >= lengths[beforeIndex][afterIndex + 1]) {
      diff.push({ type: "delete", text: beforeLines[beforeIndex] });
      beforeIndex += 1;
    } else {
      diff.push({ type: "insert", text: afterLines[afterIndex] });
      afterIndex += 1;
    }
  }
  while (beforeIndex < beforeLines.length) {
    diff.push({ type: "delete", text: beforeLines[beforeIndex] });
    beforeIndex += 1;
  }
  while (afterIndex < afterLines.length) {
    diff.push({ type: "insert", text: afterLines[afterIndex] });
    afterIndex += 1;
  }
  return diff;
}

export function prepareMarkdownEditPreview(
  request: AgentTurnRequest,
  response: AgentTurnResponse
): MarkdownEditPreview {
  if (response.documentId !== request.documentId) {
    throw new Error("응답 문서가 요청 문서와 일치하지 않습니다.");
  }
  if (response.baseVersion !== request.baseVersion) {
    throw new Error("응답의 문서 version이 요청과 일치하지 않습니다.");
  }
  if (response.result.action !== "markdown_edit" || !response.result.edit) {
    throw new Error("Markdown 편집 응답이 아닙니다.");
  }
  if (!targetsMatch(request, response.result.edit)) {
    throw new Error("응답의 편집 대상이 요청 범위와 일치하지 않습니다.");
  }
  const edit = response.result.edit;
  const actualTarget = edit.actual_target;
  if (!edit.replacement_markdown.trim()) {
    throw new Error("비어 있는 Markdown 편집 결과는 적용할 수 없습니다.");
  }
  if (edit.operation === "insert_after" && actualTarget.type !== "current_section") {
    throw new Error("이어 쓰기는 현재 section에서만 적용할 수 있습니다.");
  }

  const sourceLines = request.editorSnapshot.markdown.split("\n");
  if (actualTarget.start_line < 1
    || actualTarget.end_line < actualTarget.start_line
    || actualTarget.end_line > sourceLines.length) {
    throw new Error("Markdown 편집 범위가 문서 경계를 벗어났습니다.");
  }

  const replacementLines = edit.replacement_markdown.split("\n");
  const originalLines = sourceLines.slice(actualTarget.start_line - 1, actualTarget.end_line);
  const nextLines = [...sourceLines];
  if (edit.operation === "replace") {
    nextLines.splice(actualTarget.start_line - 1, originalLines.length, ...replacementLines);
  } else {
    nextLines.splice(actualTarget.end_line, 0, ...replacementLines);
  }

  return {
    operation: edit.operation,
    summary: edit.summary,
    replacementMarkdown: edit.replacement_markdown,
    nextMarkdown: nextLines.join("\n"),
    diffLines: edit.operation === "replace"
      ? lineDiff(originalLines.join("\n"), edit.replacement_markdown)
      : replacementLines.map((text) => ({ type: "insert" as const, text }))
  };
}

export function validateMarkdownEditApplication(
  request: AgentTurnRequest,
  response: AgentTurnResponse,
  currentContext: Pick<ActiveMarkdownEditContext, "documentId" | "editorSnapshot">
): void {
  prepareMarkdownEditPreview(request, response);
  if (currentContext.documentId !== request.documentId
    || currentContext.editorSnapshot.markdown !== request.editorSnapshot.markdown) {
    throw new Error("요청 이후 문서가 변경되었습니다. 최신 내용으로 재생성해주세요.");
  }
}
