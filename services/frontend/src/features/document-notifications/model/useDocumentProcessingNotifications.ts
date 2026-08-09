"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useUserPreferences } from "@/entities/user";
import type { DocumentItemResponse } from "@/entities/document";
import type { DocumentStatus } from "@/entities/tree";
import { publishNotice, subscribeNotices, type NoticePayload } from "./noticeBus";

export type DocumentProcessingNotice = NoticePayload & { id: string };

const NOTICE_DURATION_MS = 6000;

function wasProcessing(status: DocumentStatus | undefined) {
  return status === "uploaded" || status === "processing";
}

function noticeText(kind: DocumentProcessingNotice["kind"], count: number) {
  if (kind === "completed") {
    return {
      title: "문서 처리 완료",
      message: count === 1 ? "문서 분석이 완료되었습니다." : `${count}개 문서 분석이 완료되었습니다.`
    };
  }
  return {
    title: "문서 처리 실패",
    message: count === 1 ? "문서를 처리하지 못했습니다." : `${count}개 문서를 처리하지 못했습니다.`
  };
}

export function useDocumentProcessingNotifications(documents: DocumentItemResponse[]) {
  const { preferences } = useUserPreferences();
  const {
    browser: browserNotifications,
    completed: completedNotifications,
    failed: failedNotifications
  } = preferences.notifications;
  const [notices, setNotices] = useState<DocumentProcessingNotice[]>([]);
  const previousStatusesRef = useRef<Map<string, DocumentStatus> | null>(null);
  const timersRef = useRef<number[]>([]);

  const dismissNotice = useCallback((id: string) => {
    setNotices((current) => current.filter((notice) => notice.id !== id));
  }, []);

  // 카드 표시 + 백그라운드 탭이면 브라우저 알림까지. 모든 알림이 이 경로를 지난다.
  const pushNotice = useCallback((notice: NoticePayload) => {
    const id = `${notice.kind}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setNotices((current) => [...current, { id, ...notice }]);
    // 액션이 있는 카드는 사용자가 선택할 때까지 남긴다.
    if (!notice.action) {
      timersRef.current.push(window.setTimeout(() => dismissNotice(id), NOTICE_DURATION_MS));
    }

    if (
      browserNotifications
      && document.visibilityState === "hidden"
      && "Notification" in window
      && Notification.permission === "granted"
    ) {
      new Notification(notice.title, { body: notice.message });
    }
  }, [browserNotifications, dismissNotice]);

  useEffect(() => () => {
    timersRef.current.forEach((timer) => window.clearTimeout(timer));
  }, []);

  // 다른 feature(질의·AI 작업)가 버스로 발행한 알림을 같은 스택에 표시한다.
  useEffect(() => subscribeNotices(pushNotice), [pushNotice]);

  useEffect(() => {
    const currentStatuses = new Map(documents.map((document) => [document.id, document.status]));
    const previousStatuses = previousStatusesRef.current;
    previousStatusesRef.current = currentStatuses;
    if (!previousStatuses) return;

    const counts = { completed: 0, failed: 0 };
    documents.forEach((document) => {
      const previousStatus = previousStatuses.get(document.id);
      if (!wasProcessing(previousStatus)) return;
      if (document.status === "completed") counts.completed += 1;
      if (document.status === "failed") counts.failed += 1;
    });

    (["completed", "failed"] as const).forEach((kind) => {
      const enabled = kind === "completed" ? completedNotifications : failedNotifications;
      if (counts[kind] === 0 || !enabled) return;
      // 버스를 거쳐야 알림 패널 히스토리에도 남는다. 카드 표시는 구독 경로(pushNotice)가 처리한다.
      publishNotice({ kind, ...noticeText(kind, counts[kind]) });
    });
  }, [documents, completedNotifications, failedNotifications]);

  return { notices, dismissNotice };
}
