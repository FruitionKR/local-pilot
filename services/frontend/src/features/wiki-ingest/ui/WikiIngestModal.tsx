"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { fileIcon, plusIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import { isWikiReflectEligible } from "../model/wikiReflectState";
import styles from "./WikiIngestModal.module.css";
import type { DocumentItemResponse } from "@/entities/document";

/**
 * Ingest 대상 문서를 고르는 중앙 모달. 검색 모달(DocumentSearch)과 동일한 portal/overlay 구조를 쓴다.
 * 이미 최신이거나 처리 중인 문서는 재요청해도 의미가 없어 목록에서 제외한다.
 */
export function WikiIngestModal({
  documents,
  onSubmit,
  onClose
}: {
  documents: DocumentItemResponse[];
  onSubmit: (documents: DocumentItemResponse[]) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(new Set());
  const inputRef = useRef<HTMLInputElement | null>(null);

  const eligibleDocuments = useMemo(() => documents.filter(isWikiReflectEligible), [documents]);
  const normalizedQuery = query.trim().toLowerCase();
  const visibleDocuments = useMemo(
    () =>
      normalizedQuery
        ? eligibleDocuments.filter((document) => document.filename.toLowerCase().includes(normalizedQuery))
        : eligibleDocuments,
    [eligibleDocuments, normalizedQuery]
  );

  useEffect(() => {
    inputRef.current?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  function toggleDocument(documentId: string) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(documentId)) {
        next.delete(documentId);
      } else {
        next.add(documentId);
      }
      return next;
    });
  }

  function handleSubmit() {
    const targets = eligibleDocuments.filter((document) => selectedIds.has(document.id));
    if (targets.length === 0) return;
    onSubmit(targets);
    onClose();
  }

  // 사이드바(z-index 스태킹 컨텍스트) 내부에 렌더되면 그래프 등에 가려지므로 body로 portal한다.
  return createPortal(
    <div className={styles["ingest-overlay"]} onClick={onClose}>
      <div
        className={styles["ingest-modal"]}
        role="dialog"
        aria-modal="true"
        aria-label="위키에 반영할 문서 선택"
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles["ingest-header"]}>
          <input
            ref={inputRef}
            type="search"
            placeholder="문서명 검색"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <button type="button" className={styles["ingest-close"]} aria-label="닫기" onClick={onClose}>
            <SvgIcon src={plusIcon} className={styles["ingest-close-icon"]} />
          </button>
        </div>

        <div className={styles["ingest-body"]}>
          {eligibleDocuments.length === 0 ? (
            <p className={styles["ingest-empty"]}>위키에 반영할 문서가 없습니다.</p>
          ) : visibleDocuments.length === 0 ? (
            <p className={styles["ingest-empty"]}>검색 결과가 없습니다.</p>
          ) : (
            <div className={styles["ingest-results"]} role="group" aria-label="위키 반영 대상 문서">
              {visibleDocuments.map((document) => (
                <label key={document.id} className={styles["ingest-result"]}>
                  <input
                    type="checkbox"
                    checked={selectedIds.has(document.id)}
                    onChange={() => toggleDocument(document.id)}
                  />
                  <SvgIcon src={fileIcon} className={styles["ingest-result-icon"]} />
                  <span className={styles["ingest-result-label"]} title={document.filename}>
                    {document.filename}
                  </span>
                </label>
              ))}
            </div>
          )}
        </div>

        <div className={styles["ingest-footer"]}>
          <span className={styles["ingest-count"]}>{selectedIds.size}개 선택됨</span>
          <button
            type="button"
            className={styles["ingest-submit"]}
            disabled={selectedIds.size === 0}
            onClick={handleSubmit}
          >
            위키에 반영
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
