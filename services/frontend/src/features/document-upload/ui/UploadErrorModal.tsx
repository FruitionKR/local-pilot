"use client";

/** 지원하지 않는 파일 업로드 시 표시하는 모달 (Figma 512:10792) */
export function UploadErrorModal({ onConfirm }: { onConfirm: () => void }) {
  return (
    <div className="modal-backdrop" role="presentation" onClick={onConfirm}>
      <div
        className="modal-card"
        role="alertdialog"
        aria-labelledby="upload-error-title"
        onClick={(event) => event.stopPropagation()}
      >
        <svg className="modal-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
          <path d="M12 2.5 23 21H1L12 2.5Zm0 6.1c-.6 0-1 .4-1 1v4.2c0 .6.4 1 1 1s1-.4 1-1V9.6c0-.6-.4-1-1-1Zm0 8.2a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 0 0 0-2.4Z" />
        </svg>
        <h2 id="upload-error-title">지원하지 않는 파일입니다.</h2>
        <p>현재는 md, txt, pdf 파일만 지원합니다.</p>
        <button type="button" className="modal-confirm-button" onClick={onConfirm}>
          확인
        </button>
      </div>
    </div>
  );
}
