"use client";

import { useEffect, useRef } from "react";
import { useUserPreferences } from "@/entities/user";
import { reflectDocumentToWiki } from "@/entities/document";
import type { DocumentItemResponse } from "@/entities/document";
import { getErrorMessage } from "@/shared/lib/errors";
import { fetchWikiMaintenanceStatus, requestWikiLint } from "../api/wikiLint";
import { publishNotice } from "./noticeBus";

// 연속 업로드를 한 장의 카드로 묶기 위한 대기 시간
const INGEST_PROMPT_DEBOUNCE_MS = 3_000;
// uploaded 상태로 이 시간 이상 머물면 리마인드 카드를 띄운다.
const INGEST_STALL_MS = 60_000;
const STALL_CHECK_INTERVAL_MS = 30_000;
// 연속 완료 시 같은 제안 카드가 반복되지 않게 최소 간격을 둔다.
const LINT_CHECK_COOLDOWN_MS = 5 * 60_000;
// 새로고침해도 이미 보여준 제안 카드가 반복되지 않도록 제안 기록을 보존한다.
const PROMPT_MEMORY_STORAGE_KEY = "fruition.pending_work_prompted";

type PromptMemory = { prompted?: string[]; stalled?: string[]; reingest?: string[] };

function loadPromptMemory(): PromptMemory {
  if (typeof window === "undefined") return {};
  try {
    return (JSON.parse(window.localStorage.getItem(PROMPT_MEMORY_STORAGE_KEY) ?? "null") ?? {}) as PromptMemory;
  } catch {
    // 손상된 저장값은 무시하고 새로 쌓는다.
    return {};
  }
}

function savePromptMemory(memory: Required<PromptMemory>) {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(PROMPT_MEMORY_STORAGE_KEY, JSON.stringify(memory));
  } catch {
    // 저장 실패는 다음 새로고침에 카드가 한 번 더 보일 뿐이므로 무시한다.
  }
}

function publishIngestPrompt(candidates: DocumentItemResponse[], title: string) {
  // PDF 원본은 ingest 대상이 아니므로 분석 제안에서 제외한다.
  const documents = candidates.filter((document) => document.mime_type !== "application/pdf");
  if (documents.length === 0) return;
  const message = documents.length === 1
    ? `"${documents[0].filename}" 문서를 위키에 반영하려면 분석을 시작하세요.`
    : `${documents.length}개 문서를 위키에 반영하려면 분석을 시작하세요.`;
  publishNotice({
    kind: "info",
    title,
    message,
    action: {
      label: "분석 시작",
      onAction: () => {
        void Promise.allSettled(
          documents.map((document) => reflectDocumentToWiki(document.id, document.document_role))
        ).then((results) => {
          // 실패 사유는 백엔드 원문을 그대로 보여준다. 개수만 알려주면 원인을 알 수 없다.
          const failures = results.flatMap((result, index) =>
            result.status === "rejected"
              ? [`${documents[index].filename}: ${getErrorMessage(result.reason, "요청에 실패했습니다.")}`]
              : []
          );
          if (failures.length > 0) {
            publishNotice({
              kind: "failed",
              title: "분석 시작 실패",
              message: failures.join(" / ")
            });
          }
        });
      }
    }
  });
}

/**
 * 사용자 개입이 필요한 상황을 감지해 알림 카드를 발행한다.
 * - ingest 시작 제안: 업로드 직후(연속 업로드 묶음) / 세션 진입 시 uploaded 문서 존재 / 60초 정체 리마인드
 * - 재분석 제안: 마지막 ingest 이후 편집된 문서(needs_reingest) 감지
 * - lint 필요: ingest 완료 직후 유지보수 상태(needs_lint)를 확인
 */
export function usePendingWorkNotifications(documents: DocumentItemResponse[]) {
  const { preferences } = useUserPreferences();
  const lintSuggestEnabled = preferences.notifications.lint;
  const documentsRef = useRef(documents);
  documentsRef.current = documents;
  const knownIdsRef = useRef<Set<string> | null>(null);
  const promptedIdsRef = useRef(new Set<string>());
  const promptQueueRef = useRef(new Set<string>());
  const promptTimerRef = useRef<number | null>(null);
  const uploadedFirstSeenRef = useRef(new Map<string, number>());
  const stallNotifiedRef = useRef(new Set<string>());
  const previousStatusesRef = useRef<Map<string, string> | null>(null);
  const reingestNotifiedRef = useRef(new Set<string>());
  const lintCheckInFlightRef = useRef(false);
  const lastLintCheckAtRef = useRef(0);
  // 새로고침 후에도 같은 카드를 다시 띄우지 않도록 제안 기록을 localStorage에서 복원한다.
  const promptMemoryLoadedRef = useRef(false);

  function restorePromptMemory() {
    if (promptMemoryLoadedRef.current) return;
    promptMemoryLoadedRef.current = true;
    const memory = loadPromptMemory();
    memory.prompted?.forEach((id) => promptedIdsRef.current.add(id));
    memory.stalled?.forEach((id) => stallNotifiedRef.current.add(id));
    memory.reingest?.forEach((id) => reingestNotifiedRef.current.add(id));
  }

  function persistPromptMemory() {
    savePromptMemory({
      prompted: [...promptedIdsRef.current],
      stalled: [...stallNotifiedRef.current],
      reingest: [...reingestNotifiedRef.current]
    });
  }

  // ① 업로드 직후 / ② 세션 진입 시: uploaded 문서에 분석 시작 제안
  useEffect(() => {
    restorePromptMemory();
    const known = knownIdsRef.current;
    const uploaded = documents.filter((document) => document.status === "uploaded");

    if (!known) {
      // 첫 스냅샷: 이미 대기 중인 문서가 있으면 즉시 제안 (세션 재진입 케이스)
      knownIdsRef.current = new Set(documents.map((document) => document.id));
      const initial = uploaded.filter((document) => !promptedIdsRef.current.has(document.id));
      initial.forEach((document) => promptedIdsRef.current.add(document.id));
      persistPromptMemory();
      publishIngestPrompt(initial, "분석 대기 중인 문서가 있습니다");
      return;
    }

    // 이후: 새로 등장한 uploaded 문서를 디바운스로 묶어 제안 (연속 업로드 대응)
    const fresh = uploaded.filter(
      (document) => !known.has(document.id) && !promptedIdsRef.current.has(document.id)
    );
    documents.forEach((document) => known.add(document.id));
    if (fresh.length > 0) {
      fresh.forEach((document) => {
        promptedIdsRef.current.add(document.id);
        promptQueueRef.current.add(document.id);
      });
      persistPromptMemory();
      if (promptTimerRef.current) window.clearTimeout(promptTimerRef.current);
      promptTimerRef.current = window.setTimeout(() => {
        promptTimerRef.current = null;
        const queuedIds = promptQueueRef.current;
        promptQueueRef.current = new Set();
        const targets = documentsRef.current.filter(
          (document) => queuedIds.has(document.id) && document.status === "uploaded"
        );
        publishIngestPrompt(targets, "새 문서가 업로드되었습니다");
      }, INGEST_PROMPT_DEBOUNCE_MS);
    }
  }, [documents]);

  useEffect(() => () => {
    if (promptTimerRef.current) window.clearTimeout(promptTimerRef.current);
  }, []);

  // ③ 60초 정체 리마인드: 제안을 넘겼거나 시작이 실패한 문서를 다시 잡아준다
  useEffect(() => {
    restorePromptMemory();
    function checkStalledUploads() {
      const now = Date.now();
      const firstSeen = uploadedFirstSeenRef.current;
      const currentIds = new Set<string>();

      const stalled: DocumentItemResponse[] = [];
      documentsRef.current.forEach((document) => {
        if (document.status !== "uploaded") return;
        currentIds.add(document.id);
        const seenAt = firstSeen.get(document.id) ?? now;
        if (!firstSeen.has(document.id)) firstSeen.set(document.id, now);
        if (now - seenAt >= INGEST_STALL_MS && !stallNotifiedRef.current.has(document.id)) {
          stalled.push(document);
          stallNotifiedRef.current.add(document.id);
        }
      });
      // uploaded를 벗어난 문서는 추적에서 제거한다 (재업로드 시 다시 감지)
      [...firstSeen.keys()].forEach((id) => {
        if (!currentIds.has(id)) {
          firstSeen.delete(id);
          stallNotifiedRef.current.delete(id);
          promptedIdsRef.current.delete(id);
        }
      });
      persistPromptMemory();

      publishIngestPrompt(stalled, "문서 분석이 아직 시작되지 않았습니다");
    }

    checkStalledUploads();
    const timer = window.setInterval(checkStalledUploads, STALL_CHECK_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [documents]);

  // 재분석 제안: 마지막 ingest 이후 편집된 문서(needs_reingest)를 감지한다.
  // 재분석이 시작되면 needs_reingest가 풀리므로, 그때 알림 기록을 해제해 다음 편집 때 다시 제안한다.
  useEffect(() => {
    restorePromptMemory();
    // 문서 목록을 아직 받기 전(빈 배열)에는 복원한 알림 기록을 지우면 안 된다.
    // 여기서 지우면 목록 도착 시 같은 카드가 새로고침마다 다시 발행된다.
    if (documents.length === 0) return;
    const needing = documents.filter((document) => document.needs_reingest);
    const needingIds = new Set(needing.map((document) => document.id));
    [...reingestNotifiedRef.current].forEach((id) => {
      if (!needingIds.has(id)) reingestNotifiedRef.current.delete(id);
    });

    const fresh = needing.filter((document) => !reingestNotifiedRef.current.has(document.id));
    if (fresh.length === 0) {
      persistPromptMemory();
      return;
    }
    fresh.forEach((document) => reingestNotifiedRef.current.add(document.id));
    persistPromptMemory();
    publishIngestPrompt(fresh, "마지막 분석 이후 수정된 문서가 있습니다");
  }, [documents]);

  // lint 필요 감지: 문서가 completed로 전이하면 유지보수 상태를 확인한다 (DB 비교라 저렴)
  useEffect(() => {
    const currentStatuses = new Map(documents.map((document) => [document.id, document.status as string]));
    const previousStatuses = previousStatusesRef.current;
    previousStatusesRef.current = currentStatuses;
    if (!previousStatuses || !lintSuggestEnabled) return;

    const hasNewCompletion = documents.some((document) =>
      document.status === "completed" && previousStatuses.get(document.id) !== "completed"
      && previousStatuses.has(document.id)
    );
    if (!hasNewCompletion) return;
    if (lintCheckInFlightRef.current) return;
    if (Date.now() - lastLintCheckAtRef.current < LINT_CHECK_COOLDOWN_MS) return;

    lintCheckInFlightRef.current = true;
    lastLintCheckAtRef.current = Date.now();
    fetchWikiMaintenanceStatus()
      .then(({ needs_lint }) => {
        if (!needs_lint) return;
        publishNotice({
          kind: "info",
          title: "Lint 제안",
          message: "새 문서 반영 후 위키 페이지가 변경되었습니다. Lint를 실행할 수 있습니다.",
          action: {
            label: "Lint",
            onAction: () => {
              requestWikiLint(false)
                .then(({ changedPageCount }) => {
                  publishNotice({
                    kind: "completed",
                    title: "Lint 완료",
                    message: `${changedPageCount}개 페이지를 다듬었습니다.`
                  });
                })
                .catch((error: unknown) => {
                  publishNotice({
                    kind: "failed",
                    title: "Lint 실패",
                    message: error instanceof Error ? error.message : "Lint 요청에 실패했습니다."
                  });
                });
            }
          }
        });
      })
      .catch(() => {
        // 상태 조회 실패는 제안을 못 띄울 뿐이므로 조용히 넘긴다. 다음 완료 시 재시도된다.
      })
      .finally(() => {
        lintCheckInFlightRef.current = false;
      });
  }, [documents, lintSuggestEnabled]);
}
