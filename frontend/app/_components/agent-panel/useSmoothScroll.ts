import { useCallback, useEffect, useRef } from "react";

const SCROLL_DURATION_MS = 1100;

/**
 * 스크롤 컨테이너에 cubic-ease 애니메이션 스크롤을 제공하는 훅.
 * AgentBody에서 추출했습니다.
 */
export function useSmoothScroll(containerRef: React.RefObject<HTMLDivElement | null>) {
  const animationRef = useRef<number | null>(null);

  const scrollToPosition = useCallback((targetTop: number, { immediate = false } = {}) => {
    const container = containerRef.current;
    if (!container) return;

    if (animationRef.current !== null) {
      window.cancelAnimationFrame(animationRef.current);
    }

    const maxTop = Math.max(container.scrollHeight - container.clientHeight, 0);
    const nextTop = Math.max(0, Math.min(targetTop, maxTop));
    const startTop = container.scrollTop;
    const distance = nextTop - startTop;

    if (Math.abs(distance) <= 1) return;
    if (immediate) {
      container.scrollTop = nextTop;
      return;
    }

    const startTime = performance.now();

    function animateScroll(now: number) {
      if (!container) return;
      const elapsed = Math.min((now - startTime) / SCROLL_DURATION_MS, 1);
      const eased = elapsed < 0.5
        ? 4 * elapsed * elapsed * elapsed
        : 1 - Math.pow(-2 * elapsed + 2, 3) / 2;

      container.scrollTop = startTop + distance * eased;

      if (elapsed < 1) {
        animationRef.current = window.requestAnimationFrame(animateScroll);
        return;
      }

      animationRef.current = null;
    }

    animationRef.current = window.requestAnimationFrame(animateScroll);
  }, [containerRef]);

  // 언마운트 시 진행 중인 애니메이션 취소
  useEffect(() => () => {
    if (animationRef.current !== null) {
      window.cancelAnimationFrame(animationRef.current);
    }
  }, []);

  return { scrollToPosition, animationRef };
}
