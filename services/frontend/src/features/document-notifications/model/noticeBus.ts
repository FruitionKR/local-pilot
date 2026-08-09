// 알림 카드 스택으로 알림을 보내는 전역 pub/sub.
// 알림 카드는 DocumentProcessingNotifications가 렌더링하므로,
// 다른 feature(채팅 질의 등)는 이 버스로 발행만 한다.

export type NoticePayload = {
  kind: "completed" | "failed" | "info";
  title: string;
  message: string;
  /** 있으면 취소/실행 2버튼 카드로 렌더한다 (Figma 673:3870). 자동 닫힘 없음. */
  action?: { label: string; onAction: () => void };
};

export type NoticeRecord = NoticePayload & { id: string; createdAt: number };

type NoticeListener = (notice: NoticePayload) => void;

const listeners = new Set<NoticeListener>();

// 알림 패널에서 지난 알림을 볼 수 있게 발행 이력을 메모리에 보관한다.
const HISTORY_LIMIT = 50;
let history: NoticeRecord[] = [];
const historyListeners = new Set<() => void>();

export function publishNotice(notice: NoticePayload) {
  const record: NoticeRecord = {
    id: `${notice.kind}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    createdAt: Date.now(),
    ...notice
  };
  history = [record, ...history].slice(0, HISTORY_LIMIT);
  listeners.forEach((listener) => listener(notice));
  historyListeners.forEach((listener) => listener());
}

export function subscribeNotices(listener: NoticeListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getNoticeHistory(): NoticeRecord[] {
  return history;
}

export function subscribeNoticeHistory(listener: () => void) {
  historyListeners.add(listener);
  return () => {
    historyListeners.delete(listener);
  };
}
