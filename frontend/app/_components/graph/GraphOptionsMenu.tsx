import { MoreHorizontal } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { GRAPH_CACHE_KEY } from "../../_lib/graph";

/** graph-topbar의 "그래프 옵션"(⋯) 메뉴. 현재는 저장된 레이아웃을 초기화한다. */
export function GraphOptionsMenu() {
  const [isOpen, setIsOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!isOpen) return;

    function handleOutsidePointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutsidePointerDown);
    return () => document.removeEventListener("mousedown", handleOutsidePointerDown);
  }, [isOpen]);

  function resetLayout() {
    setIsOpen(false);
    if (typeof window === "undefined") return;
    window.localStorage.removeItem(GRAPH_CACHE_KEY);
    window.location.reload();
  }

  return (
    <div className="graph-options" ref={rootRef}>
      <button
        type="button"
        aria-label="그래프 옵션"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <MoreHorizontal size={16} />
      </button>
      {isOpen && (
        <div className="graph-options-menu" role="menu">
          <button type="button" role="menuitem" onClick={resetLayout}>
            레이아웃 초기화
          </button>
        </div>
      )}
    </div>
  );
}
