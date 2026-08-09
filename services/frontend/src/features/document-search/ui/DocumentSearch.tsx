"use client";

import { useEffect, useRef, type MouseEvent as ReactMouseEvent } from "react";
import { createPortal } from "react-dom";
import { formatRelativeTime } from "@/shared/lib/time";
import { fileIcon, plusIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import type { SelectableTreeItem } from "@/widgets/document-sidebar/model/types";
import type { Project } from "@/entities/tree/model/tree";
import { useDocumentSearch, type SearchHit } from "../model/useDocumentSearch";
import styles from "./DocumentSearch.module.css";

/** 중앙 검색 모달 (Figma 757:17248). 문서명을 클라이언트 필터링하고, 결과 클릭 시 해당 문서를 연다. */
export function DocumentSearch({
  projects,
  onSelectGraphNode,
  onClose
}: {
  projects: Project[];
  onSelectGraphNode: (item: SelectableTreeItem) => void;
  onClose: () => void;
}) {
  const { query, setQuery, normalizedQuery, results, overflowCount } = useDocumentSearch(projects);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    inputRef.current?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  function handleSelect(event: ReactMouseEvent<HTMLButtonElement>, item: SearchHit) {
    event.stopPropagation();
    onSelectGraphNode(item);
    onClose();
  }

  // 사이드바(z-index 스태킹 컨텍스트) 내부에 렌더되면 편집기 등에 가려지므로 body로 portal한다.
  return createPortal(
    <div className={styles["search-overlay"]} onClick={onClose}>
      <div
        className={styles["search-modal"]}
        role="dialog"
        aria-modal="true"
        aria-label="채팅 및 프로젝트 검색"
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles["search-header"]}>
          <input
            ref={inputRef}
            type="search"
            placeholder="채팅 및 프로젝트 검색"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <button type="button" className={styles["search-close"]} aria-label="검색 닫기" onClick={onClose}>
            <SvgIcon src={plusIcon} className={styles["search-close-icon"]} />
          </button>
        </div>
        <div className={styles["search-body"]}>
          {normalizedQuery ? (
            <div className={styles["search-results"]} role="group" aria-label="문서 검색 결과">
              {results.length > 0 ? (
                <>
                  {results.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={styles["search-result"]}
                      onClick={(event) => handleSelect(event, item)}
                    >
                      <span className={styles["search-result-title"]}>
                        <SvgIcon src={fileIcon} className={styles["search-result-icon"]} />
                        <span className={styles["search-result-label"]}>{item.label}</span>
                      </span>
                      <span className={styles["search-result-meta"]}>
                        {item.updatedAt ? formatRelativeTime(item.updatedAt) : item.projectTitle}
                      </span>
                    </button>
                  ))}
                  {overflowCount > 0 ? (
                    <p className={styles["search-more"]}>외 {overflowCount}개 더 있습니다. 검색어를 좁혀 주세요.</p>
                  ) : null}
                </>
              ) : (
                <p className={styles["search-empty"]}>검색 결과가 없습니다.</p>
              )}
            </div>
          ) : (
            <p className={styles["search-empty"]}>문서명을 입력해 검색하세요.</p>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}
