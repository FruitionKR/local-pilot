"use client";

import type { DocumentItemResponse } from "@/entities/document";
import { useDocumentProcessingNotifications } from "../model/useDocumentProcessingNotifications";
import { useOperationNotifications } from "../model/useOperationNotifications";
import { usePendingWorkNotifications } from "../model/usePendingWorkNotifications";
import styles from "./DocumentProcessingNotifications.module.css";

/** 우하단 알림 카드 스택 (Figma 673:3870). 문서 처리 + AI 작업(lint·restore) + 대기 작업 감지 + 버스 발행 알림. */
export function DocumentProcessingNotifications({
  documents
}: {
  documents: DocumentItemResponse[];
}) {
  const { notices, dismissNotice } = useDocumentProcessingNotifications(documents);
  useOperationNotifications();
  usePendingWorkNotifications(documents);

  if (notices.length === 0) return null;

  return (
    <aside className={styles["notice-stack"]} aria-label="문서 처리 알림">
      {notices.map((notice) => (
        <div
          key={notice.id}
          className={styles["notice"]}
          role={notice.kind === "failed" ? "alert" : "status"}
        >
          <div className={styles["notice-title"]}>
            <strong>{notice.title}</strong>
            <p>{notice.message}</p>
          </div>
          {notice.action ? (
            <div className={styles["notice-buttons"]}>
              <button
                type="button"
                className={styles["notice-cancel"]}
                onClick={() => dismissNotice(notice.id)}
              >
                취소
              </button>
              <button
                type="button"
                className={styles["notice-confirm"]}
                onClick={() => {
                  notice.action?.onAction();
                  dismissNotice(notice.id);
                }}
              >
                {notice.action.label}
              </button>
            </div>
          ) : (
            <button
              type="button"
              className={styles["notice-confirm"]}
              onClick={() => dismissNotice(notice.id)}
            >
              확인
            </button>
          )}
        </div>
      ))}
    </aside>
  );
}
