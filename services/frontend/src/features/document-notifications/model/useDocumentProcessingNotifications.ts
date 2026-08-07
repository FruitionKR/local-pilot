"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useUserPreferences } from "@/entities/user";
import type { DocumentItemResponse } from "@/entities/document";
import type { DocumentStatus } from "@/entities/tree";

export type DocumentProcessingNotice = {
  id: string;
  kind: "completed" | "failed";
  title: string;
  message: string;
};

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

  useEffect(() => () => {
    timersRef.current.forEach((timer) => window.clearTimeout(timer));
  }, []);

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

      const text = noticeText(kind, counts[kind]);
      const id = `${kind}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      setNotices((current) => [...current, { id, kind, ...text }]);
      timersRef.current.push(window.setTimeout(() => dismissNotice(id), NOTICE_DURATION_MS));

      if (
        browserNotifications
        && document.visibilityState === "hidden"
        && "Notification" in window
        && Notification.permission === "granted"
      ) {
        new Notification(text.title, { body: text.message });
      }
    });
  }, [
    dismissNotice,
    documents,
    browserNotifications,
    completedNotifications,
    failedNotifications
  ]);

  return { notices, dismissNotice };
}
