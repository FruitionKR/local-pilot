import { useEffect, useRef, useState } from "react";
import { NoteContentConflictError, saveNoteDraft } from "../api/note";
import { composeEditableNoteMarkdown } from "@/entities/document/lib/note";
import type { NoteSaveStatus } from "@/entities/tree/model/tree";
import {
  applyRequiredAgentSource,
  mergePendingNoteSave,
  planAgentRetryAfterFailure,
  recoverPendingNoteSaveAfterAgentFailure,
  type PendingNoteSave
} from "./pendingSave";

const AUTOSAVE_DELAY_MS = 800;
// AI 편집은 에디터에 이미 반영된 뒤라, 저장에 실패하면 사용자가 다시 편집하지 않아도 스스로 다시 보낸다.
const AGENT_RETRY_MAX_ATTEMPTS = 3;
const AGENT_RETRY_BASE_MS = 1000;

export function useNoteAutosave({
  documentId,
  marker,
  initialVersion
}: {
  documentId: string;
  marker: string;
  initialVersion: number;
}) {
  const [status, setStatus] = useState<NoteSaveStatus>("saved");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [contentVersion, setContentVersion] = useState(initialVersion);
  const versionRef = useRef(initialVersion);
  const revisionRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const saveInFlightRef = useRef(false);
  const pendingSaveRef = useRef<PendingNoteSave | null>(null);
  const conflictRef = useRef(false);
  const agentRetryRequiredRef = useRef(false);
  const agentRetryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const agentRetryAttemptsRef = useRef(0);

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    if (agentRetryTimerRef.current) clearTimeout(agentRetryTimerRef.current);
  }, []);

  function cancelAgentRetry() {
    if (agentRetryTimerRef.current) clearTimeout(agentRetryTimerRef.current);
    agentRetryTimerRef.current = null;
  }

  function scheduleAgentRetry(
    candidate: PendingNoteSave,
    recovered: { pending: PendingNoteSave | null; retryRequired: boolean }
  ) {
    const plan = planAgentRetryAfterFailure(
      recovered,
      agentRetryAttemptsRef.current,
      AGENT_RETRY_MAX_ATTEMPTS,
      AGENT_RETRY_BASE_MS
    );
    agentRetryAttemptsRef.current = plan.attempts;
    if (!plan.shouldRetry) return;
    cancelAgentRetry();
    agentRetryTimerRef.current = setTimeout(() => {
      agentRetryTimerRef.current = null;
      if (conflictRef.current) return;
      void flushSave(candidate);
    }, plan.delayMs);
  }

  async function flushSave(candidate: PendingNoteSave): Promise<boolean> {
    const saveCandidate = applyRequiredAgentSource(candidate, agentRetryRequiredRef.current);
    if (conflictRef.current) return false;
    if (saveInFlightRef.current) {
      pendingSaveRef.current = mergePendingNoteSave(pendingSaveRef.current, saveCandidate);
      return true;
    }

    saveInFlightRef.current = true;
    setStatus("saving");
    setErrorMessage(null);
    try {
      const saved = await saveNoteDraft(
        documentId,
        saveCandidate.markdown,
        versionRef.current,
        saveCandidate.source
      );
      versionRef.current = saved.content_version;
      setContentVersion(saved.content_version);
      if (saveCandidate.source === "agent") {
        agentRetryRequiredRef.current = false;
        agentRetryAttemptsRef.current = 0;
        cancelAgentRetry();
      }
      setStatus(saveCandidate.revision === revisionRef.current ? "saved" : "dirty");
      return true;
    } catch (error) {
      if (error instanceof NoteContentConflictError) {
        conflictRef.current = true;
        cancelAgentRetry();
        setStatus("conflict");
      } else {
        if (saveCandidate.source === "agent") {
          const recovery = recoverPendingNoteSaveAfterAgentFailure(pendingSaveRef.current);
          pendingSaveRef.current = recovery.pending;
          agentRetryRequiredRef.current = recovery.retryRequired;
          scheduleAgentRetry(saveCandidate, recovery);
        }
        setStatus("error");
      }
      setErrorMessage(error instanceof Error ? error.message : "노트를 저장하지 못했습니다.");
      return false;
    } finally {
      saveInFlightRef.current = false;
      const pending = pendingSaveRef.current;
      pendingSaveRef.current = null;
      if (pending && !conflictRef.current) void flushSave(pending);
    }
  }

  function queueSave(body: string, source?: "agent") {
    if (conflictRef.current) return;
    // 새 저장이 밀린 AI 편집분을 그대로 싣고 가므로 예약된 재시도는 버린다.
    cancelAgentRetry();
    revisionRef.current += 1;
    const candidate = {
      markdown: composeEditableNoteMarkdown(marker, body),
      revision: revisionRef.current,
      source: source ?? (agentRetryRequiredRef.current ? "agent" : undefined)
    };
    setStatus("dirty");
    setErrorMessage(null);
    if (timerRef.current) clearTimeout(timerRef.current);
    if (source === "agent") {
      timerRef.current = null;
      void flushSave(candidate);
      return;
    }
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      void flushSave(candidate);
    }, AUTOSAVE_DELAY_MS);
  }

  /** 디바운스를 건너뛰고 즉시 저장한다 (Cmd/Ctrl+S, 저장 버튼). 성공 여부를 반환한다. */
  function saveNow(body: string): Promise<boolean> {
    if (conflictRef.current) return Promise.resolve(false);
    cancelAgentRetry();
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    revisionRef.current += 1;
    const candidate = {
      markdown: composeEditableNoteMarkdown(marker, body),
      revision: revisionRef.current,
      source: agentRetryRequiredRef.current ? ("agent" as const) : undefined
    };
    setErrorMessage(null);
    return flushSave(candidate);
  }

  return { status, errorMessage, contentVersion, queueSave, saveNow };
}
