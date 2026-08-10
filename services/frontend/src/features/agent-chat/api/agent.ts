import { apiFetch, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { AgentTurnRequest, AgentTurnResponse } from "../lib/markdownAgent";

export async function requestAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/agent/turn`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request)
  });
  const run = await parseJsonOrThrow<AgentTurnRunResponse>(response, ERROR_MESSAGES.agentTurnFailed);
  for (let attempt = 0; attempt < 300; attempt += 1) {
    if (run.status === "completed" && run.result) return run as AgentTurnResponse;
    if (run.status === "failed") throw new Error(run.error || ERROR_MESSAGES.agentTurnFailed);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    const polled = await apiFetch(
      `/api/workspaces/${encodeURIComponent(workspaceId)}/agent/turn/${encodeURIComponent(run.requestId)}`,
      { cache: "no-store" }
    );
    Object.assign(run, await parseJsonOrThrow<AgentTurnRunResponse>(polled, ERROR_MESSAGES.agentTurnFailed));
  }
  throw new Error("AI 편집 처리 시간이 초과되었습니다.");
}

type AgentTurnRunResponse = Omit<AgentTurnResponse, "status" | "result"> & {
  status: string;
  result: AgentTurnResponse["result"] | null;
  error?: string | null;
};
