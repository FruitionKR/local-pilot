import { useEffect, useRef, useState } from "react";
import { NoteContentConflictError, saveNoteDraft } from "../api/note";
import { composeEditableNoteMarkdown } from "@/entities/document/lib/note";
import type { NoteSaveStatus } from "@/entities/tree/model/tree";
import {
  applyRequiredAgentSource,
  mergePendingNoteSave,
  recoverPendingNoteSaveAfterAgentFailure,
  type PendingNoteSave
} from "./pendingSave";

const AUTOSAVE_DELAY_MS = 800;

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

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
  }, []);

  async function flushSave(candidate: PendingNoteSave) {
    const saveCandidate = applyRequiredAgentSource(candidate, agentRetryRequiredRef.current);
    if (conflictRef.current) return;
    if (saveInFlightRef.current) {
      pendingSaveRef.current = mergePendingNoteSave(pendingSaveRef.current, saveCandidate);
      return;
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
      if (saveCandidate.source === "agent") agentRetryRequiredRef.current = false;
      setStatus(saveCandidate.revision === revisionRef.current ? "saved" : "dirty");
    } catch (error) {
      if (error instanceof NoteContentConflictError) {
        conflictRef.current = true;
        setStatus("conflict");
      } else {
        if (saveCandidate.source === "agent") {
          const recovery = recoverPendingNoteSaveAfterAgentFailure(pendingSaveRef.current);
          pendingSaveRef.current = recovery.pending;
          agentRetryRequiredRef.current = recovery.retryRequired;
        }
        setStatus("error");
      }
      setErrorMessage(error instanceof Error ? error.message : "노트를 저장하지 못했습니다.");
    } finally {
      saveInFlightRef.current = false;
      const pending = pendingSaveRef.current;
      pendingSaveRef.current = null;
      if (pending && !conflictRef.current) void flushSave(pending);
    }
  }

  function queueSave(body: string, source?: "agent") {
    if (conflictRef.current) return;
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

  return { status, errorMessage, contentVersion, queueSave };
}
