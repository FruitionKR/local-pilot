/**
 * unknown 에러에서 사람이 읽을 메시지를 안전하게 추출합니다.
 * Error가 아니면 fallback 문구를 반환합니다.
 */
export function getErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export function isErrorMessage(error: unknown, message: string): boolean {
  return error instanceof Error && error.message === message;
}
