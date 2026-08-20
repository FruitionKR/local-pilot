export type StatusStepState = "done" | "active" | "pending";
export type StatusStep = [label: string, state: StatusStepState];

export function buildStatusSteps(isLoading: boolean, hasResponse: boolean): StatusStep[] {
  return [
    ["질문 분석", isLoading || hasResponse ? "done" : "pending"],
    ["관련 Wiki page 검색", hasResponse ? "done" : isLoading ? "active" : "pending"],
    ["근거 기반 답변 작성", hasResponse ? "done" : "pending"]
  ];
}

export function buildDocumentCommandSteps(
  action: DocumentCommandAction,
  isLoading: boolean,
  hasResponse: boolean
): StatusStep[] {
  return [
    ["질문 분석", isLoading || hasResponse ? "done" : "pending"],
    ["LLM 답변 생성", hasResponse ? "done" : isLoading ? "active" : "pending"],
    [documentCommandLabel(action), hasResponse ? "done" : "pending"]
  ];
}

function documentCommandLabel(action: DocumentCommandAction): string {
  if (action === "markdown_edit") return "문서 편집";
  if (action === "markdown_create") return "새 문서 생성";
  if (action === "folder_organize") return "폴더 정리";
  if (action === "workspace_workflow") return "워크스페이스 작업";
  return "Skill 작성";
}
import type { DocumentCommandAction } from "./markdownAgent";
