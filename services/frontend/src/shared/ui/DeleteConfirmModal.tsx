"use client";

import { AlertModal } from "./AlertModal";

/** 문서/폴더 삭제 전 확인 모달 (AlertModal 재사용) */
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
    <AlertModal
      titleId="delete-confirm-title"
      title={isFolder ? "폴더를 삭제하시겠습니까?" : "문서를 삭제하시겠습니까?"}
      description={
        <>
          「{target.label}」{isFolder ? " 폴더와 폴더 안의 모든 항목이 삭제됩니다." : " 문서가 삭제됩니다."} 이 작업은 되돌릴 수 없습니다.
        </>
      }
      onClose={onCancel}
    >
      <div className="modal-actions">
        <button type="button" className="modal-cancel-button" onClick={onCancel}>취소</button>
        <button type="button" className="modal-delete-button" onClick={onConfirm}>삭제</button>
      </div>
    </AlertModal>
  );
}
