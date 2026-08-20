import { apiFetch, parseJsonOrThrow, getWorkspaceId, workspacePath, ERROR_MESSAGES } from "@/shared/api/client";
import { pollUntil } from "@/shared/lib/polling";
import type { AgentTurnRequest, AgentTurnResponse } from "../lib/markdownAgent";

/** run 응답이 종료 상태면 결과를 반환하고, 실패면 던지고, 진행 중이면 null을 반환한다. */
function toTerminalResult(run: AgentTurnRunResponse): AgentTurnResponse | null {
  if (run.status === "completed" && run.result) return run as AgentTurnResponse;
  if (run.status === "failed") throw new Error(run.error || ERROR_MESSAGES.agentTurnFailed);
  return null;
}

export async function requestAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(workspacePath(workspaceId, "agent", "turn"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request)
  });
  const run = await parseJsonOrThrow<AgentTurnRunResponse>(response, ERROR_MESSAGES.agentTurnFailed);
  // 생성 응답이 이미 종료 상태면 첫 대기 없이 바로 반환한다.
  const initial = toTerminalResult(run);
  if (initial) return initial;
  return pollUntil({
    poll: async () => {
      const polled = await apiFetch(
        workspacePath(workspaceId, "agent", "turn", run.requestId),
        { cache: "no-store" }
      );
      return toTerminalResult(await parseJsonOrThrow<AgentTurnRunResponse>(polled, ERROR_MESSAGES.agentTurnFailed));
    },
    timeoutMessage: "AI 편집 처리 시간이 초과되었습니다."
  });
}

type AgentTurnRunResponse = Omit<AgentTurnResponse, "status" | "result"> & {
  status: string;
  result: AgentTurnResponse["result"] | null;
  error?: string | null;
};
