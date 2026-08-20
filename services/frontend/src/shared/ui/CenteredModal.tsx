"use client";

import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { cx } from "@/shared/lib/classNames";
import { useEscapeKey } from "@/shared/lib/useEscapeKey";
import styles from "./CenteredModal.module.css";

/**
 * 중앙 정렬 모달 공통 셸. overlay 클릭·Escape로 닫히고, 내부 클릭은 전파를 막는다.
 * 사이드바(z-index 스태킹 컨텍스트) 내부에 렌더되면 편집기 등에 가려지므로 body로 portal한다.
 */
export function CenteredModal({
  ariaLabel,
  className,
  onClose,
  children
}: {
  ariaLabel: string;
  /** 모달 박스(.modal-box)에 덧붙일 소비자 측 클래스 */
  className?: string;
  onClose: () => void;
  children: ReactNode;
}) {
  useEscapeKey(true, onClose);

  return createPortal(
    <div className={styles["modal-overlay"]} onClick={onClose}>
      <div
        className={cx(styles["modal-box"], className)}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        onClick={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>,
    document.body
  );
}
