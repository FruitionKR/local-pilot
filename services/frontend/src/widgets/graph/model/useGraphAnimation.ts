import type { MutableRefObject } from "react";
import { useEffect } from "react";
import { useUserPreferences } from "@/entities/user";

/** 첫 프레임에서 이전 프레임 시각이 없을 때 사용하는 delta 기본값(ms) */
const INITIAL_FRAME_DELTA_MS = 16;
/** 물리 tick 실행 간격(ms) */
const PHYSICS_TICK_INTERVAL_MS = 32;

export function useGraphAnimation({
  tickGraphRef,
  advanceHoverAnimationRef,
  drawGraphRef,
  draggingNodeIdRef,
  scheduleGraphCacheWrite,
  requestAnimationRef
}: {
  /** simulation을 한 tick 진행. 아직 움직이는 중이면 true. */
  tickGraphRef: MutableRefObject<() => boolean>;
  advanceHoverAnimationRef: MutableRefObject<(deltaMs: number) => boolean>;
  drawGraphRef: MutableRefObject<() => void>;
  draggingNodeIdRef: MutableRefObject<string | null>;
  scheduleGraphCacheWrite: () => void;
  /** simulation이나 hover가 다시 활성화될 때 RAF를 깨우는 함수. */
  requestAnimationRef: MutableRefObject<() => void>;
}) {
  const { reduceMotion } = useUserPreferences();

  useEffect(() => {
    let frameId = 0;
    let lastFrame = 0;
    let lastHoverFrame = 0;

    const requestNextFrame = () => {
      if (frameId !== 0) return;
      frameId = requestAnimationFrame(animate);
    };

    const animate = (time: number) => {
      frameId = 0;
      if (reduceMotion) {
        let graphChanged = false;
        const tickLimit = draggingNodeIdRef.current ? 1 : 300;
        for (let tickIndex = 0; tickIndex < tickLimit; tickIndex += 1) {
          if (!tickGraphRef.current()) break;
          graphChanged = true;
        }
        const hoverChanged = advanceHoverAnimationRef.current(Number.POSITIVE_INFINITY);
        if (graphChanged) scheduleGraphCacheWrite();
        if (graphChanged || hoverChanged) drawGraphRef.current();
        if (graphChanged || hoverChanged || draggingNodeIdRef.current) requestNextFrame();
        return;
      }

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
        if (isActive || hoverChanged || draggingNodeIdRef.current) requestNextFrame();
      } else if (hoverChanged) {
        drawGraphRef.current();
        requestNextFrame();
      } else {
        requestNextFrame();
      }
    };

    requestAnimationRef.current = requestNextFrame;
    requestNextFrame();
    return () => {
      requestAnimationRef.current = () => {};
      if (frameId !== 0) cancelAnimationFrame(frameId);
    };
  }, [
    advanceHoverAnimationRef,
    draggingNodeIdRef,
    drawGraphRef,
    reduceMotion,
    requestAnimationRef,
    scheduleGraphCacheWrite,
    tickGraphRef
  ]);
}
