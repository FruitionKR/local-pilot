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

type NoticeListener = (notice: NoticePayload) => void;

const listeners = new Set<NoticeListener>();

export function publishNotice(notice: NoticePayload) {
  listeners.forEach((listener) => listener(notice));
}

export function subscribeNotices(listener: NoticeListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
