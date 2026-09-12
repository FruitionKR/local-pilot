/**
 * unknown 에러에서 사람이 읽을 메시지를 안전하게 추출합니다.
 * Error가 아니면 fallback 문구를 반환합니다.
 */
export function getErrorMessage(error: unknown, fallback: string): string {
  if (isErrorMessage(error, "agent_not_configured")) {
    return "서버 설정으로 인해 AI 작업 기능을 사용할 수 없습니다. 관리자에게 문의해 주세요.";
  }
  if (isErrorMessage(error, "agent_turn_route_contract_failed")) {
    return "AI의 작업 분류 응답을 처리하지 못했습니다. 다시 시도해 주세요.";
  }
  return error instanceof Error ? error.message : fallback;
}

export function isErrorMessage(error: unknown, message: string): boolean {
  return error instanceof Error && error.message === message;
}
