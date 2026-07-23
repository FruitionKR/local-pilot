import { apiFetch, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { AgentTurnRequest, AgentTurnResponse } from "../markdownAgent";

export async function requestAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/agent/turn`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request)
  });
  return parseJsonOrThrow<AgentTurnResponse>(response, ERROR_MESSAGES.agentTurnFailed);
}
