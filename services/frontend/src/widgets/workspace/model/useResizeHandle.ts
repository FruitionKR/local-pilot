import { useRef, useState, type PointerEvent as ReactPointerEvent } from "react";

/**
 * pointer capture 기반의 폭 리사이즈 상태를 관리하는 훅.
 * HomeWorkspace의 sidebar/source preview 리사이즈 로직에서 추출했습니다.
 * update/stop은 자신의 pointerId 이벤트를 처리했는지 여부를 반환한다.
 */
export function useResizeHandle(defaultWidth: number, minWidth: number, getMaxWidth: () => number) {
  const [width, setWidth] = useState(defaultWidth);
  const resizeRef = useRef<{ pointerId: number; startX: number; startWidth: number } | null>(null);

  function start(event: ReactPointerEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    resizeRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: width
    };
  }

  function update(event: ReactPointerEvent<HTMLElement>): boolean {
    const resize = resizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return false;
    const nextWidth = Math.min(getMaxWidth(), Math.max(minWidth, resize.startWidth + event.clientX - resize.startX));
    setWidth(nextWidth);
    return true;
  }

  function stop(event: ReactPointerEvent<HTMLElement>): boolean {
    const resize = resizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return false;
    resizeRef.current = null;
    return true;
  }

  return { width, start, update, stop };
}
