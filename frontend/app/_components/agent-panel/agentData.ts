export type StatusStepState = "done" | "active" | "pending";
export type StatusStep = [label: string, state: StatusStepState, time: string];

export const agentResults = [
  ["또래 관계 연구.pdf", "p.14-17 · 정서 발달 상관관계 분석"],
  ["정서 발달 보고서.pdf", "p.3, p.21 · 사회적 상호작용 사례"]
] as const;

export function buildStatusSteps(timed: boolean): StatusStep[] {
  return [
    ["업로드된 문서 12건 스캔 완료", "done", timed ? "14:22:24" : ""],
    ["'또래 관계' 관련 페이지 8건 추출", "done", timed ? "14:24:04" : ""],
    ["정서 발달 연관 논문 분석 중", "active", timed ? "14:24:58" : ""],
    ["발표용 핵심 자료 정리", "pending", timed ? "14:25:07" : ""]
  ];
}
