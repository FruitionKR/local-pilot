import type { ActiveMarkdownEditContext, MarkdownEditorSnapshot } from "./markdownEditContext";

export type AgentTurnRequest = {
  documentId: string;
  baseVersion: number;
  message: string;
  conversationContext?: { recentConversationSummary: string };
  editorSnapshot: MarkdownEditorSnapshot;
};

export type AgentTurnAction = "chat_answer" | "markdown_edit" | "markdown_create" | "clarify" | "reject";

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

type AgentTurnResultSummary = Pick<AgentTurnResult, "action" | "message" | "chat" | "edit" | "generated_markdown">;
const MAX_LCS_CELLS = 250_000;

export function buildAgentTurnRequest(
  message: string,
  context: ActiveMarkdownEditContext,
  recentConversationSummary?: string
): AgentTurnRequest {
  const summary = recentConversationSummary?.trim();
  return {
    documentId: context.documentId,
    baseVersion: context.baseVersion,
    message,
    // 선택한 채팅 맥락이 있을 때만 실어 보낸다. 비면 필드 자체를 생략(현행과 동일).
    ...(summary ? { conversationContext: { recentConversationSummary: summary } } : {}),
    editorSnapshot: context.editorSnapshot
  };
}

export function describeAgentTurnResult(result: AgentTurnResultSummary): string {
  if (result.action === "markdown_edit") {
    return result.edit?.summary ?? "Markdown 편집 제안을 받았습니다.";
  }
  if (result.action === "markdown_create") {
    return result.generated_markdown?.summary ?? "새 Markdown 초안을 받았습니다.";
  }
  if (result.action === "chat_answer") {
    return result.chat?.answer ?? "Agent 응답을 받았습니다.";
  }
  return result.message ?? "요청을 처리하려면 추가 정보가 필요합니다.";
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
