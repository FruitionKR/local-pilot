import type { DragEvent as ReactDragEvent } from "react";
import type { DropPosition } from "../../_lib/types";

export function resolveDropPosition(event: ReactDragEvent<HTMLButtonElement>): DropPosition {
  const rect = event.currentTarget.getBoundingClientRect();
  const offsetY = event.clientY - rect.top;
  if (offsetY < rect.height * 0.28) return "before";
  if (offsetY > rect.height * 0.72) return "after";
  return "inside";
}

export function setLightDragPreview(event: ReactDragEvent<HTMLButtonElement>) {
  const source = event.currentTarget;
  const rect = source.getBoundingClientRect();
  const preview = source.cloneNode(true) as HTMLElement;

  preview.style.position = "fixed";
  preview.style.top = "-1000px";
  preview.style.left = "-1000px";
  preview.style.width = `${rect.width}px`;
  preview.style.height = `${rect.height}px`;
  preview.style.opacity = "0.06";
  preview.style.background = "rgba(255, 255, 255, 0.24)";
  preview.style.border = "1px solid rgba(207, 215, 227, 0.18)";
  preview.style.boxShadow = "none";
  preview.style.pointerEvents = "none";
  preview.style.zIndex = "-1";
  document.body.appendChild(preview);

  event.dataTransfer.setDragImage(preview, 14, Math.min(18, rect.height / 2));
  window.setTimeout(() => preview.remove(), 0);
}
