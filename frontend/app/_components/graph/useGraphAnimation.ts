import type { MutableRefObject } from "react";
import { useEffect } from "react";
import type { NodePositionMap } from "../../_lib/types";

export function useGraphAnimation({
  tickGraphRef,
  nodePositionsRef,
  draggingNodeIdRef,
  drawGraphRef,
  scheduleGraphCacheWrite
}: {
  tickGraphRef: MutableRefObject<(positions: NodePositionMap, anchorId: string | null) => NodePositionMap>;
  nodePositionsRef: MutableRefObject<NodePositionMap>;
  draggingNodeIdRef: MutableRefObject<string | null>;
  drawGraphRef: MutableRefObject<() => void>;
  scheduleGraphCacheWrite: () => void;
}) {
  useEffect(() => {
    let frameId = 0;
    let lastFrame = 0;

    const animate = (time: number) => {
      if (time - lastFrame > 32) {
        lastFrame = time;
        const anchorId = draggingNodeIdRef.current;
        const next = tickGraphRef.current(nodePositionsRef.current, anchorId);
        if (next !== nodePositionsRef.current) {
          nodePositionsRef.current = next;
          scheduleGraphCacheWrite();
        }
        drawGraphRef.current();
      }

      frameId = requestAnimationFrame(animate);
    };

    frameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(frameId);
  }, [draggingNodeIdRef, drawGraphRef, nodePositionsRef, scheduleGraphCacheWrite, tickGraphRef]);
}
