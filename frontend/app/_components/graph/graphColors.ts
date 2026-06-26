/**
 * 그래프 캔버스 색상 상수.
 * CSS 변수(dark/base.css)와 동일한 값을 유지합니다:
 *   --source-page: #bbcf6c
 *   --concept-page: #fffdf0
 *   --yellow: #ffc117
 *   --line: #4f4f4f
 *   --muted: #a7a7a7
 *   --ink: #f0f0f0
 */
export const GRAPH_COLORS = {
  sourcePage: "#bbcf6c",
  conceptPage: "#fffdf0",
  hoverNode: "#ffc117",
  rawNodeFill: "#4f4f4f",
  rawNodeStroke: "#a7a7a7",
  rawSourceLink: "#5a5a5a",
  baseLink: "#4f4f4f",
  hoverLink: "#ffc117",
  sourceLabelInk: "#f0f0f0",
  conceptLabelMuted: "#8a8a8a"
} as const;

/** "#rrggbb" hex를 [r, g, b] 정수 배열로 변환한다. */
export function hexToRgb(hex: string): [number, number, number] {
  const normalized = hex.replace("#", "");
  return [
    parseInt(normalized.slice(0, 2), 16),
    parseInt(normalized.slice(2, 4), 16),
    parseInt(normalized.slice(4, 6), 16)
  ];
}

/** 두 hex 색을 amount(0~1) 비율로 선형 보간해 "rgb(...)" 문자열을 만든다. */
export function mixHexColor(from: string, to: string, amount: number): string {
  const fromRgb = hexToRgb(from);
  const toRgb = hexToRgb(to);
  const red = Math.round(fromRgb[0] + (toRgb[0] - fromRgb[0]) * amount);
  const green = Math.round(fromRgb[1] + (toRgb[1] - fromRgb[1]) * amount);
  const blue = Math.round(fromRgb[2] + (toRgb[2] - fromRgb[2]) * amount);
  return `rgb(${red}, ${green}, ${blue})`;
}
