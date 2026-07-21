import type { ActiveMarkdownEditContext, MarkdownEditorSnapshot } from "./markdownEditContext";

export type AgentTurnRequest = {
  documentId: string;
  baseVersion: number;
  message: string;
  editorSnapshot: MarkdownEditorSnapshot;
};

export type AgentTurnAction = "chat_answer" | "markdown_edit" | "markdown_create" | "clarify" | "reject";

export type AgentTurnEdit = {
  operation: "replace" | "insert_after";
  target: {
    type: "selection" | "current_section" | "whole_document";
    start_line: number;
    end_line: number;
  };
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

export type AgentTurnResponse = {
  documentId: string;
  baseVersion: number;
  requestId: string;
  result: AgentTurnResult;
};

type AgentTurnResultSummary = Pick<AgentTurnResult, "action" | "message" | "chat" | "edit" | "generated_markdown">;

export function buildAgentTurnRequest(
  message: string,
  context: ActiveMarkdownEditContext
): AgentTurnRequest {
  return {
    documentId: context.documentId,
    baseVersion: context.baseVersion,
    message,
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
