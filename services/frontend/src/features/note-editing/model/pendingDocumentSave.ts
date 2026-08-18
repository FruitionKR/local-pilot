const pendingDocumentSaves = new Map<string, Promise<unknown>>();

/** 같은 문서를 다시 열 때 진행 중인 저장이 끝난 뒤 최신 version을 조회하도록 Promise를 공유한다. */
export function trackPendingDocumentSave(documentId: string, save: Promise<unknown>): void {
  const previous = pendingDocumentSaves.get(documentId);
  const tracked = previous
    ? Promise.allSettled([previous, save])
    : save.then(() => undefined, () => undefined);
  pendingDocumentSaves.set(documentId, tracked);
  void tracked.finally(() => {
    if (pendingDocumentSaves.get(documentId) === tracked) pendingDocumentSaves.delete(documentId);
  });
}

export async function waitForPendingDocumentSave(documentId: string): Promise<void> {
  await pendingDocumentSaves.get(documentId);
}
