/** ms만큼 대기하는 Promise를 반환한다. */
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

type PollUntilOptions<T> = {
  /** 최대 시도 횟수. 기본 300회. */
  attempts?: number;
  /** 시도 간 대기 시간(ms). 기본 1000ms. */
  intervalMs?: number;
  /** 완료면 결과를, 계속 폴링해야 하면 null을 반환한다. 실패는 throw로 전달한다. */
  poll: () => Promise<T | null>;
  /** 시도 횟수를 소진했을 때 던질 에러 메시지. */
  timeoutMessage: string;
};

/** poll이 결과를 반환할 때까지 매 시도 전 intervalMs 대기 후 반복 호출한다. */
export async function pollUntil<T>(options: PollUntilOptions<T>): Promise<T> {
  const { attempts = 300, intervalMs = 1000, poll, timeoutMessage } = options;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await sleep(intervalMs);
    const result = await poll();
    if (result !== null) return result;
  }
  throw new Error(timeoutMessage);
}
