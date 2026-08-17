import { useEffect, useRef, useState } from "react";
import { NoteContentConflictError, saveNoteDraft } from "../api/note";
import { composeEditableNoteMarkdown } from "@/entities/document/lib/note";
import type { NoteSaveStatus } from "@/entities/tree/model/tree";
import {
  applyRequiredAgentSource,
  mergePendingNoteSave,
  planAgentRetryAfterFailure,
  recoverPendingNoteSaveAfterAgentFailure,
  selectDetachedSaveCandidate,
  type PendingNoteSave
} from "./pendingSave";
import { trackPendingDocumentSave } from "./pendingDocumentSave";

export type DetachedNoteSaveResult =
  | { success: true }
  | { success: false; error: unknown };

const AUTOSAVE_DELAY_MS = 800;
// AI 편집은 에디터에 이미 반영된 뒤라, 저장에 실패하면 사용자가 다시 편집하지 않아도 스스로 다시 보낸다.
const AGENT_RETRY_MAX_ATTEMPTS = 3;
const AGENT_RETRY_BASE_MS = 1000;

export function useNoteAutosave({
  documentId,
  marker,
  initialVersion,
  onDetachedSaveComplete
}: {
  documentId: string;
  marker: string;
  initialVersion: number;
  onDetachedSaveComplete?: (result: DetachedNoteSaveResult) => void;
}) {
  const [status, setStatus] = useState<NoteSaveStatus>("saved");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [contentVersion, setContentVersion] = useState(initialVersion);
  const versionRef = useRef(initialVersion);
  const revisionRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduledSaveRef = useRef<PendingNoteSave | null>(null);
  const saveInFlightRef = useRef(false);
  const pendingSaveRef = useRef<PendingNoteSave | null>(null);
  const conflictRef = useRef(false);
  const agentRetryRequiredRef = useRef(false);
  const agentRetryApplyOperationIdRef = useRef<string | undefined>(undefined);
  const agentRetryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const agentRetryCandidateRef = useRef<PendingNoteSave | null>(null);
  const agentRetryAttemptsRef = useRef(0);
  const mountedRef = useRef(true);
  const flushSaveRef = useRef<(candidate: PendingNoteSave) => Promise<boolean>>(async () => false);
  const onDetachedSaveCompleteRef = useRef(onDetachedSaveComplete);
  onDetachedSaveCompleteRef.current = onDetachedSaveComplete;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = null;
      if (agentRetryTimerRef.current) clearTimeout(agentRetryTimerRef.current);
      agentRetryTimerRef.current = null;

      // 디바운스·AI 재시도 대기 중 이동해도 마지막 편집분을 잃지 않도록 즉시 저장한다.
      const scheduled = selectDetachedSaveCandidate(
        scheduledSaveRef.current,
        agentRetryCandidateRef.current
      );
      scheduledSaveRef.current = null;
      agentRetryCandidateRef.current = null;
      if (scheduled && !conflictRef.current) void flushSaveRef.current(scheduled);
    };
  }, []);

  function cancelAgentRetry() {
    if (agentRetryTimerRef.current) clearTimeout(agentRetryTimerRef.current);
    agentRetryTimerRef.current = null;
    agentRetryCandidateRef.current = null;
  }

  function scheduleAgentRetry(
    candidate: PendingNoteSave,
    recovered: { pending: PendingNoteSave | null; retryRequired: boolean }
  ) {
    if (!mountedRef.current) return;
    const plan = planAgentRetryAfterFailure(
      recovered,
      agentRetryAttemptsRef.current,
      AGENT_RETRY_MAX_ATTEMPTS,
      AGENT_RETRY_BASE_MS
    );
    agentRetryAttemptsRef.current = plan.attempts;
    if (!plan.shouldRetry) {
      agentRetryCandidateRef.current = null;
      return;
    }
    cancelAgentRetry();
    agentRetryCandidateRef.current = candidate;
    agentRetryTimerRef.current = setTimeout(() => {
      agentRetryTimerRef.current = null;
      agentRetryCandidateRef.current = null;
      if (conflictRef.current) return;
      void trackedFlushSave(candidate);
    }, plan.delayMs);
  }

  async function flushSave(candidate: PendingNoteSave): Promise<boolean> {
    const saveCandidate = applyRequiredAgentSource(
      candidate,
      agentRetryRequiredRef.current,
      agentRetryApplyOperationIdRef.current
    );
    if (conflictRef.current) return false;
    if (saveInFlightRef.current) {
      pendingSaveRef.current = mergePendingNoteSave(pendingSaveRef.current, saveCandidate);
      return true;
    }

    saveInFlightRef.current = true;
    if (mountedRef.current) {
      setStatus("saving");
      setErrorMessage(null);
    }
    try {
      if (saveCandidate.source === "agent") {
        agentRetryApplyOperationIdRef.current = saveCandidate.applyOperationId;
      }
      const saved = await saveNoteDraft(
        documentId,
        saveCandidate.markdown,
        versionRef.current,
        saveCandidate.source,
        saveCandidate.applyOperationId
      );
      versionRef.current = saved.content_version;
      if (mountedRef.current) setContentVersion(saved.content_version);
      if (saveCandidate.source === "agent") {
        agentRetryApplyOperationIdRef.current = undefined;
        agentRetryRequiredRef.current = false;
        agentRetryAttemptsRef.current = 0;
        cancelAgentRetry();
      }
      if (mountedRef.current) {
        setStatus(saveCandidate.revision === revisionRef.current ? "saved" : "dirty");
      } else {
        onDetachedSaveCompleteRef.current?.({ success: true });
      }
      return true;
    } catch (error) {
      if (error instanceof NoteContentConflictError) {
        conflictRef.current = true;
        cancelAgentRetry();
        if (mountedRef.current) setStatus("conflict");
      } else {
        if (saveCandidate.source === "agent") {
          const recovery = recoverPendingNoteSaveAfterAgentFailure(pendingSaveRef.current);
          pendingSaveRef.current = recovery.pending;
          agentRetryRequiredRef.current = recovery.retryRequired;
          scheduleAgentRetry(saveCandidate, recovery);
        }
        if (mountedRef.current) setStatus("error");
      }
      if (mountedRef.current) {
        setErrorMessage(error instanceof Error ? error.message : "노트를 저장하지 못했습니다.");
      } else {
        onDetachedSaveCompleteRef.current?.({ success: false, error });
      }
      return false;
    } finally {
      saveInFlightRef.current = false;
      const pending = pendingSaveRef.current;
      pendingSaveRef.current = null;
      if (pending && !conflictRef.current) void trackedFlushSave(pending);
    }
  }

  function trackedFlushSave(candidate: PendingNoteSave): Promise<boolean> {
    const save = flushSave(candidate);
    trackPendingDocumentSave(documentId, save);
    return save;
  }
  flushSaveRef.current = trackedFlushSave;

  function queueSave(body: string, source?: "agent", applyOperationId?: string) {
    if (conflictRef.current) return;
    // 새 저장이 밀린 AI 편집분을 그대로 싣고 가므로 예약된 재시도는 버린다.
    cancelAgentRetry();
    revisionRef.current += 1;
    const saveSource = source ?? (agentRetryRequiredRef.current ? "agent" : undefined);
    const candidate = {
      markdown: composeEditableNoteMarkdown(marker, body),
      revision: revisionRef.current,
      source: saveSource,
      applyOperationId: saveSource === "agent"
        ? applyOperationId ?? agentRetryApplyOperationIdRef.current
        : undefined
    };
    setStatus("dirty");
    setErrorMessage(null);
    if (timerRef.current) clearTimeout(timerRef.current);
    if (source === "agent") {
      timerRef.current = null;
      scheduledSaveRef.current = null;
      void trackedFlushSave(candidate);
      return;
    }
    scheduledSaveRef.current = candidate;
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      const scheduled = scheduledSaveRef.current;
      scheduledSaveRef.current = null;
      if (scheduled) void trackedFlushSave(scheduled);
    }, AUTOSAVE_DELAY_MS);
  }

  /** 디바운스를 건너뛰고 즉시 저장한다 (Cmd/Ctrl+S). 성공 여부를 반환한다. */
  function saveNow(body: string): Promise<boolean> {
    if (conflictRef.current) return Promise.resolve(false);
    cancelAgentRetry();
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    scheduledSaveRef.current = null;
    revisionRef.current += 1;
    const saveSource = agentRetryRequiredRef.current ? ("agent" as const) : undefined;
    const candidate = {
      markdown: composeEditableNoteMarkdown(marker, body),
      revision: revisionRef.current,
      source: saveSource,
      applyOperationId: saveSource === "agent" ? agentRetryApplyOperationIdRef.current : undefined
    };
    setErrorMessage(null);
    return trackedFlushSave(candidate);
  }

  return { status, errorMessage, contentVersion, queueSave, saveNow };
}
