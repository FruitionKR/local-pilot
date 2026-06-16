export type StatusStepState = "done" | "active" | "pending";
export type StatusStep = [label: string, state: StatusStepState];

export function buildStatusSteps(isLoading: boolean, hasResponse: boolean): StatusStep[] {
  return [
    ["질문 분석", isLoading || hasResponse ? "done" : "pending"],
    ["관련 Wiki page 검색", hasResponse ? "done" : isLoading ? "active" : "pending"],
    ["근거 기반 답변 작성", hasResponse ? "done" : "pending"]
  ];
}
