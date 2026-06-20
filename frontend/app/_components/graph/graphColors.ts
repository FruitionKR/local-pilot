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
