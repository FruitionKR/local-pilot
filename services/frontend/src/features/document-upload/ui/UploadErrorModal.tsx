"use client";

import { AlertModal } from "@/shared/ui/AlertModal";

/** 지원하지 않는 파일 업로드 시 표시하는 모달 (Figma 512:10792) */
export function UploadErrorModal({ onConfirm }: { onConfirm: () => void }) {
  return (
    <AlertModal
      titleId="upload-error-title"
      title="지원하지 않는 파일입니다."
      description="현재는 md, txt, pdf 파일만 지원합니다."
      onClose={onConfirm}
    >
      <button type="button" className="modal-confirm-button" onClick={onConfirm}>
        확인
      </button>
    </AlertModal>
  );
}
