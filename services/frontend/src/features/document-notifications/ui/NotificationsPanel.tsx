"use client";

import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { fetchBackendData } from "@/entities/wiki";
import { reflectDocumentToWiki } from "@/entities/document";
import { getErrorMessage } from "@/shared/lib/errors";
import { cx } from "@/shared/lib/classNames";
import { fetchWikiMaintenanceStatus, requestWikiLint } from "../api/wikiLint";
import { plusIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import {
  getNoticeHistory,
  publishNotice,
  subscribeNoticeHistory,
  type NoticeRecord
} from "../model/noticeBus";
import styles from "./NotificationsPanel.module.css";
import type { DocumentItemResponse } from "@/entities/document";
import type { Project, TreeItem } from "@/entities/tree";

const KIND_LABELS: Record<NoticeRecord["kind"], string> = {
  completed: "완료",
  failed: "실패",
  info: "안내"
};

const TIME_FORMAT = new Intl.DateTimeFormat("ko-KR", {
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit"
});

/** 프로필 메뉴에서 여는 Wiki 관리 창: 알림 이력 확인 + Ingest·Lint 요청 실행. */
export function NotificationsPanel({ projects, onClose }: { projects: Project[]; onClose: () => void }) {
  const notices = useSyncExternalStore(subscribeNoticeHistory, getNoticeHistory, getNoticeHistory);
  // 동일한 파일명이 있을 수 있어 최상위 폴더명을 접두로 붙인다. (폴더명 자체도 중복 가능해 완전한 구별 보장은 아님)
  const folderTitleByDocumentId = useMemo(() => {
    const map = new Map<string, string>();
    function walk(item: TreeItem, folderTitle: string) {
      if (item.documentId && !map.has(item.documentId)) map.set(item.documentId, folderTitle);
      item.children?.forEach((child) => walk(child, folderTitle));
    }
    projects.forEach((project) => project.items.forEach((item) => walk(item, project.title)));
    return map;
  }, [projects]);
  const [documents, setDocuments] = useState<DocumentItemResponse[] | null>(null);
  const [documentsError, setDocumentsError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(new Set());
  const [isIngesting, setIsIngesting] = useState(false);
  const [isLinting, setIsLinting] = useState(false);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    fetchBackendData()
      .then((data) => {
        if (!cancelled) setDocuments(data.documents);
      })
      .catch((error: unknown) => {
        if (!cancelled) setDocumentsError(getErrorMessage(error, "문서 목록을 불러오지 못했습니다."));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 위키가 만들어진 뒤 마지막 다듬기 이후 변경이 있을 때만 실제 lint를 보낸다.
  // 상태는 패널을 연 뒤에도 바뀔 수 있어 클릭 시점에 새로 조회한다.
  async function runLint() {
    if (isLinting) return;
    setIsLinting(true);
    try {
      // 위키가 만들어진 뒤 마지막 다듬기 이후 변경이 있을 때만 실제 lint를 보낸다.
      const { needs_lint } = await fetchWikiMaintenanceStatus();
      if (!needs_lint) {
        publishNotice({
          kind: "info",
          title: "Lint 요청",
          message: "수정 된 Wiki의 구성요소가 없습니다."
        });
        return;
      }
      const { changedPageCount } = await requestWikiLint(false);
      publishNotice({
        kind: "completed",
        title: "위키 다듬기 완료",
        message: `${changedPageCount}개 페이지를 다듬었습니다.`
      });
    } catch (error: unknown) {
      publishNotice({
        kind: "failed",
        title: "위키 다듬기 실패",
        message: getErrorMessage(error, "위키 다듬기 요청에 실패했습니다.")
      });
    } finally {
      setIsLinting(false);
    }
  }

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

  async function runIngest() {
    const targets = (documents ?? []).filter((document) => selectedIds.has(document.id));
    if (targets.length === 0 || isIngesting) return;

    setIsIngesting(true);
    const results = await Promise.allSettled(
      targets.map((document) => reflectDocumentToWiki(document.id, document.document_role))
    );
    // 실패 사유는 백엔드 원문을 그대로 보여준다. 개수만 알려주면 원인을 알 수 없다.
    const failures = results.flatMap((result, index) =>
      result.status === "rejected"
        ? [`${targets[index].filename}: ${getErrorMessage(result.reason, "요청에 실패했습니다.")}`]
        : []
    );
    const startedCount = targets.length - failures.length;

    if (startedCount > 0) {
      publishNotice({
        kind: "completed",
        title: "Ingest 요청",
        message: `${startedCount}개 문서 처리를 시작했습니다.`
      });
    }
    if (failures.length > 0) {
      publishNotice({
        kind: "failed",
        title: "Ingest 요청 실패",
        message: failures.join(" / ")
      });
    }
    setSelectedIds(new Set());
    setIsIngesting(false);
  }

  // 사이드바 내부에 렌더되면 편집기 등에 가려지므로 body로 portal한다 (SettingsModal과 동일).
  return createPortal(
    <div className={styles.overlay} onClick={onClose}>
      <div
        className={styles.panel}
        role="dialog"
        aria-modal="true"
        aria-label="Wiki 관리"
        onClick={(event) => event.stopPropagation()}
      >
        <header className={styles.header}>
          <h2>Wiki 관리</h2>
          <button type="button" className={styles.close} aria-label="Wiki 관리 창 닫기" onClick={onClose}>
            <SvgIcon src={plusIcon} className={styles["close-icon"]} />
          </button>
        </header>

        <section className={styles.section} aria-label="알림 목록">
          {notices.length === 0 && <p className={styles.empty}>표시할 알림이 없습니다.</p>}
          {notices.map((notice) => (
            <article key={notice.id} className={styles.notice}>
              <span className={cx(styles.badge, styles[`badge-${notice.kind}`])}>{KIND_LABELS[notice.kind]}</span>
              <div className={styles["notice-body"]}>
                <strong>{notice.title}</strong>
                <p>{notice.message}</p>
                <time>{TIME_FORMAT.format(notice.createdAt)}</time>
              </div>
            </article>
          ))}
        </section>

        <section className={styles.section} aria-label="명령 실행">
          <header className={styles["section-header"]}>
            <strong>위키 반영</strong>
            <span>반영할 문서를 선택해 Ingest를 요청하세요. 수정됨 표시는 마지막 분석 이후 편집된 문서입니다.</span>
          </header>
          {documentsError && <p className={styles.empty} role="alert">{documentsError}</p>}
          {!documentsError && documents === null && <p className={styles.empty}>문서 목록을 불러오는 중입니다.</p>}
          {documents !== null && documents.length === 0 && <p className={styles.empty}>업로드된 문서가 없습니다.</p>}
          {documents !== null && documents.length > 0 && (
            <>
              {documents.some((document) => document.needs_reingest) && (
                <button
                  type="button"
                  className={styles["select-modified"]}
                  disabled={isIngesting}
                  onClick={() => {
                    setSelectedIds((current) => {
                      const next = new Set(current);
                      documents
                        .filter((document) => document.needs_reingest)
                        .forEach((document) => next.add(document.id));
                      return next;
                    });
                  }}
                >
                  수정된 문서 모두 선택
                </button>
              )}
              <ul className={styles["document-list"]}>
                {documents.map((document) => (
                  <li key={document.id}>
                    <label className={styles["document-row"]}>
                      <input
                        type="checkbox"
                        checked={selectedIds.has(document.id)}
                        disabled={isIngesting}
                        onChange={() => toggleDocument(document.id)}
                      />
                      <span className={styles["document-name"]}>
                        {folderTitleByDocumentId.has(document.id) && (
                          <span className={styles["document-folder"]}>
                            {folderTitleByDocumentId.get(document.id)} /{" "}
                          </span>
                        )}
                        {document.filename}
                      </span>
                      {document.needs_reingest && (
                        <span className={styles["document-reingest"]}>수정됨</span>
                      )}
                      <span className={styles["document-status"]}>{document.status}</span>
                    </label>
                  </li>
                ))}
              </ul>
            </>
          )}
          {/* Lint는 문서 선택과 무관한 워크스페이스 단위 작업이라 문서 목록 유무와 상관없이 노출한다 */}
          <div className={styles["command-actions"]}>
            <button
              type="button"
              className={styles["ingest-button"]}
              disabled={selectedIds.size === 0 || isIngesting}
              onClick={() => void runIngest()}
            >
              {isIngesting ? "Ingest 요청 중..." : `Ingest 요청 (${selectedIds.size})`}
            </button>
            <button
              type="button"
              className={styles["lint-button"]}
              disabled={isLinting}
              onClick={() => void runLint()}
            >
              {isLinting ? "Lint 요청 중..." : "Lint 요청"}
            </button>
          </div>
        </section>
      </div>
    </div>,
    document.body
  );
}
