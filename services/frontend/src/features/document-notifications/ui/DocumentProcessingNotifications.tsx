"use client";

import { CheckCircle2, CircleX, X } from "lucide-react";
import type { DocumentItemResponse } from "@/entities/document";
import { useDocumentProcessingNotifications } from "../model/useDocumentProcessingNotifications";
import styles from "./DocumentProcessingNotifications.module.css";

export function DocumentProcessingNotifications({
  documents
}: {
  documents: DocumentItemResponse[];
}) {
  const { notices, dismissNotice } = useDocumentProcessingNotifications(documents);

  if (notices.length === 0) return null;

  return (
    <aside className={styles["notice-stack"]} aria-label="문서 처리 알림">
      {notices.map((notice) => (
        <div
          key={notice.id}
          className={styles["notice"]}
          role={notice.kind === "failed" ? "alert" : "status"}
        >
          {notice.kind === "completed"
            ? <CheckCircle2 className={styles["is-completed"]} size={18} aria-hidden />
            : <CircleX className={styles["is-failed"]} size={18} aria-hidden />}
          <div>
            <strong>{notice.title}</strong>
            <p>{notice.message}</p>
          </div>
          <button
            type="button"
            aria-label={`${notice.title} 닫기`}
            onClick={() => dismissNotice(notice.id)}
          >
            <X size={15} aria-hidden />
          </button>
        </div>
      ))}
    </aside>
  );
}
