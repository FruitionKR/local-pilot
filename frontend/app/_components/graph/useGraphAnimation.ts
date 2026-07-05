import type { MutableRefObject } from "react";
import { useEffect } from "react";

/** 첫 프레임에서 이전 프레임 시각이 없을 때 사용하는 delta 기본값(ms) */
const INITIAL_FRAME_DELTA_MS = 16;
/** 물리 tick 실행 간격(ms) */
const PHYSICS_TICK_INTERVAL_MS = 32;

export function useGraphAnimation({
  tickGraphRef,
  advanceHoverAnimationRef,
  drawGraphRef,
  scheduleGraphCacheWrite
}: {
  /** simulation을 한 tick 진행. 아직 움직이는 중이면 true. */
  tickGraphRef: MutableRefObject<() => boolean>;
  advanceHoverAnimationRef: MutableRefObject<(deltaMs: number) => boolean>;
  drawGraphRef: MutableRefObject<() => void>;
  scheduleGraphCacheWrite: () => void;
}) {
  useEffect(() => {
    let frameId = 0;
    let lastFrame = 0;
    let lastHoverFrame = 0;

    const animate = (time: number) => {
      const hoverDelta = lastHoverFrame > 0 ? time - lastHoverFrame : INITIAL_FRAME_DELTA_MS;
      lastHoverFrame = time;
      const hoverChanged = advanceHoverAnimationRef.current(hoverDelta);

      if (time - lastFrame > PHYSICS_TICK_INTERVAL_MS) {
        lastFrame = time;
        const isActive = tickGraphRef.current();
        if (isActive) {
          scheduleGraphCacheWrite();
          drawGraphRef.current();
        } else if (hoverChanged) {
          drawGraphRef.current();
        }
      } else if (hoverChanged) {
        drawGraphRef.current();
      }

      frameId = requestAnimationFrame(animate);
    };

    frameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(frameId);
  }, [advanceHoverAnimationRef, drawGraphRef, scheduleGraphCacheWrite, tickGraphRef]);
}
