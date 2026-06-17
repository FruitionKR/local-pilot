import type { MutableRefObject } from "react";
import { useEffect } from "react";
import type { NodePositionMap } from "../../_lib/types";

export function useGraphAnimation({
  tickGraphRef,
  advanceHoverAnimationRef,
  nodePositionsRef,
  draggingNodeIdRef,
  drawGraphRef,
  scheduleGraphCacheWrite
}: {
  tickGraphRef: MutableRefObject<(positions: NodePositionMap, anchorId: string | null) => NodePositionMap>;
  advanceHoverAnimationRef: MutableRefObject<(deltaMs: number) => boolean>;
  nodePositionsRef: MutableRefObject<NodePositionMap>;
  draggingNodeIdRef: MutableRefObject<string | null>;
  drawGraphRef: MutableRefObject<() => void>;
  scheduleGraphCacheWrite: () => void;
}) {
  useEffect(() => {
    let frameId = 0;
    let lastFrame = 0;
    let lastHoverFrame = 0;

    const animate = (time: number) => {
      const hoverDelta = lastHoverFrame > 0 ? time - lastHoverFrame : 16;
      lastHoverFrame = time;
      const hoverChanged = advanceHoverAnimationRef.current(hoverDelta);

      if (time - lastFrame > 32) {
        lastFrame = time;
        const anchorId = draggingNodeIdRef.current;
        const next = tickGraphRef.current(nodePositionsRef.current, anchorId);
        if (next !== nodePositionsRef.current) {
          nodePositionsRef.current = next;
          scheduleGraphCacheWrite();
        }
        drawGraphRef.current();
      } else if (hoverChanged) {
        drawGraphRef.current();
      }

      frameId = requestAnimationFrame(animate);
    };

    frameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(frameId);
  }, [advanceHoverAnimationRef, draggingNodeIdRef, drawGraphRef, nodePositionsRef, scheduleGraphCacheWrite, tickGraphRef]);
}
