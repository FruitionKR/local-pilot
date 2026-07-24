"use client";

import { useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { DocumentProcessingState, NoteEditState, TreeItem } from "@/entities/tree";
import { cx } from "@/shared/lib/classNames";
import styles from "./DocumentSidebar.module.css";

// hover 후 tooltip이 뜨기까지의 지연(ms).
const TOOLTIP_DELAY_MS = 200;

type BadgeKind = "processing" | "stalled" | "failed";

// 처리 상태 → 뱃지 문구. stage 세부값은 tooltip으로만 노출한다.
const BADGE_LABEL: Record<BadgeKind, string> = {
  processing: "처리 중",
  stalled: "작업 중",
  failed: "실패"
};

// status(uploaded/processing/…)와 processing_state(starting/running/stalled/…)를 합쳐
// 뱃지 종류를 정한다. 우선순위: failed > stalled > processing.
function resolveBadgeKind(
  status: TreeItem["status"],
  processingState: DocumentProcessingState | undefined
): BadgeKind | null {
  if (status === "failed" || processingState === "failed") return "failed";
  if (processingState === "stalled") return "stalled";
  if (
    status === "uploading" || status === "uploaded" || status === "processing"
    || processingState === "starting" || processingState === "running"
  ) return "processing";
  return null;
}

function badgeTitle(kind: BadgeKind, processingStage?: string, errorMessage?: string): string | undefined {
  if (kind === "failed") return errorMessage ?? "처리에 실패했습니다.";
  if (kind === "stalled") return processingStage ? `작업 중: ${processingStage}` : "작업 중입니다.";
  return processingStage || undefined;
}

// 업로드 시각 기준 경과 시간을 사람이 읽는 문구로 변환한다. 렌더 시점 기준 정적 계산.
function formatElapsed(uploadedAt?: string): string | undefined {
  if (!uploadedAt) return undefined;
  const startedAt = new Date(uploadedAt).getTime();
  if (Number.isNaN(startedAt)) return undefined;
  const diffMin = Math.floor((Date.now() - startedAt) / 60000);
  if (diffMin < 0) return undefined;
  if (diffMin < 1) return "방금 업로드";
  if (diffMin < 60) return `업로드 후 ${diffMin}분 경과`;
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return `업로드 후 ${diffHour}시간 경과`;
  return `업로드 후 ${Math.floor(diffHour / 24)}일 경과`;
}

// 뱃지 상태 문구 + 경과 시간을 합쳐 툴팁 본문을 만든다.
function tooltipText(
  kind: BadgeKind,
  processingStage: string | undefined,
  errorMessage: string | undefined,
  uploadedAt: string | undefined
): string | undefined {
  const lines = [badgeTitle(kind, processingStage, errorMessage), formatElapsed(uploadedAt)].filter(Boolean);
  return lines.length ? lines.join("\n") : undefined;
}

/** 문서 처리 진행 뱃지(처리 중/작업 중/실패) + 로컬 편집 상태 점을 표시한다. */
export function TreeNodeStatus({
  status,
  processingState,
  processingStage,
  errorMessage,
  uploadedAt,
  editState
}: {
  status: TreeItem["status"];
  processingState?: DocumentProcessingState;
  processingStage?: string;
  errorMessage?: string;
  uploadedAt?: string;
  editState?: NoteEditState;
}) {
  const badgeRef = useRef<HTMLElement>(null);
  const showTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // tooltip 표시 좌표(viewport 기준). null이면 미표시. 사이드바 overflow 클리핑을 피해 body로 portal한다.
  const [tooltipAnchor, setTooltipAnchor] = useState<{ left: number; top: number } | null>(null);

  const badgeKind = resolveBadgeKind(status, processingState);
  const badgeTooltip = badgeKind ? tooltipText(badgeKind, processingStage, errorMessage, uploadedAt) : undefined;

  const openTooltip = () => {
    if (!badgeTooltip) return;
    showTimerRef.current = setTimeout(() => {
      const rect = badgeRef.current?.getBoundingClientRect();
      if (rect) setTooltipAnchor({ left: rect.left + rect.width / 2, top: rect.top - 6 });
    }, TOOLTIP_DELAY_MS);
  };
  const closeTooltip = () => {
    if (showTimerRef.current) {
      clearTimeout(showTimerRef.current);
      showTimerRef.current = null;
    }
    setTooltipAnchor(null);
  };

  const localStatus = editState?.saveStatus === "error" || editState?.saveStatus === "conflict"
    ? "error"
    : editState && (editState.saveStatus !== "saved" || editState.needsReview)
      ? "changed"
      : null;
  if (!badgeKind && !localStatus) return null;

  return (
    <>
      {badgeKind && (
        <small
          ref={badgeRef}
          className={cx(styles["tree-status"], styles[badgeKind])}
          aria-label={badgeTooltip}
          onMouseEnter={openTooltip}
          onMouseLeave={closeTooltip}
        >
          {BADGE_LABEL[badgeKind]}
          {badgeTooltip && tooltipAnchor && createPortal(
            <span
              className={styles["tree-tooltip"]}
              role="tooltip"
              style={{ left: tooltipAnchor.left, top: tooltipAnchor.top }}
            >
              {badgeTooltip}
            </span>,
            document.body
          )}
        </small>
      )}
      {localStatus && (
        <small
          className={cx(styles["tree-edit-status"], styles[`is-${localStatus}`])}
          aria-label={localStatus === "error" ? "저장 문제 있음" : "수정 사항 있음"}
          title={localStatus === "error" ? "저장 문제 있음" : "수정 사항 또는 AI 점검 대기"}
        />
      )}
    </>
  );
}
