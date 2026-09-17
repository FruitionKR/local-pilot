import { apiFetch, parseJsonOrThrow, workspacePath } from "@/shared/api/client";
import type { AgentPlan, AgentPlanRun, PlanTreeItem } from "../lib/agentPlan";

/** 채팅의 turn ID와 실제 승인 대상 run ID는 서로 다르다. */
export async function fetchAgentPlanRun(workspaceId: string, turnId: string, signal?: AbortSignal): Promise<AgentPlanRun | null> {
  const response = await apiFetch(workspacePath(workspaceId, "agent", "turn", turnId), { cache: "no-store", signal });
  const turn = await parseJsonOrThrow<{
    status: string;
    result: { run_id?: string | null } | null;
  }>(response, "작업 계획을 불러오지 못했습니다.");
  if (turn.status === "failed") throw new Error("계획 생성 요청이 실패했습니다.");
  if (turn.status === "cancelled") return { id: turnId, status: "cancelled", plan: null, error_code: null };
  if (turn.status !== "completed") return null;
  if (!turn.result?.run_id) throw new Error("이 답변의 계획 정보를 찾을 수 없습니다. 다시 요청해주세요.");
  const runResponse = await apiFetch(workspacePath(workspaceId, "agent", "runs", turn.result.run_id), { cache: "no-store", signal });
  return parseJsonOrThrow<AgentPlanRun>(runResponse, "작업 계획을 불러오지 못했습니다.");
}

export async function fetchPlanTree(workspaceId: string, signal?: AbortSignal): Promise<PlanTreeItem[]> {
  const response = await apiFetch(workspacePath(workspaceId, "document-tree"), { cache: "no-store", signal });
  return (await parseJsonOrThrow<{ items: PlanTreeItem[] }>(response, "문서와 폴더 이름을 불러오지 못했습니다.")).items;
}

export async function decideAgentPlan(workspaceId: string, runId: string, decision: "approve" | "reject", plan: AgentPlan): Promise<AgentPlanRun> {
  const response = await apiFetch(workspacePath(workspaceId, "agent", "runs", runId, decision), {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
    ...(decision === "approve" ? { body: JSON.stringify({ plan_version: plan.version, operation_hash: plan.operation_hash }) } : {})
  });
  return parseJsonOrThrow<AgentPlanRun>(response, "계획 승인·거절을 처리하지 못했습니다. 최신 상태를 확인해주세요.");
}
