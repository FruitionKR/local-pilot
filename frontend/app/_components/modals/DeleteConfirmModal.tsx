"use client";

/** 문서/폴더 삭제 전 확인 모달 (UploadErrorModal 패턴 재사용) */
export function DeleteConfirmModal({
  target,
  onConfirm,
  onCancel
}: {
  target: { label: string; kind: "folder" | "document" };
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const isFolder = target.kind === "folder";
  return (
    <div className="modal-backdrop" role="presentation" onClick={onCancel}>
      <div
        className="modal-card"
        role="alertdialog"
        aria-labelledby="delete-confirm-title"
        onClick={(event) => event.stopPropagation()}
      >
        <svg className="modal-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
          <path d="M12 2.5 23 21H1L12 2.5Zm0 6.1c-.6 0-1 .4-1 1v4.2c0 .6.4 1 1 1s1-.4 1-1V9.6c0-.6-.4-1-1-1Zm0 8.2a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 0 0 0-2.4Z" />
        </svg>
        <h2 id="delete-confirm-title">{isFolder ? "폴더를 삭제하시겠습니까?" : "문서를 삭제하시겠습니까?"}</h2>
        <p>
          「{target.label}」{isFolder ? " 폴더와 폴더 안의 모든 항목이 삭제됩니다." : " 문서가 삭제됩니다."} 이 작업은 되돌릴 수 없습니다.
        </p>
        <div className="modal-actions">
          <button type="button" className="modal-cancel-button" onClick={onCancel}>취소</button>
          <button type="button" className="modal-delete-button" onClick={onConfirm}>삭제</button>
        </div>
      </div>
    </div>
  );
}
