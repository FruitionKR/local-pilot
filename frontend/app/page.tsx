"use client";

import type {
  DragEvent as ReactDragEvent,
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  PointerEvent as ReactPointerEvent
} from "react";
import type { StaticImageData } from "next/image";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Folder,
  Menu,
  Plus,
  Search
} from "lucide-react";
import archiveIcon from "../svg/Archive.svg";
import collectionIcon from "../svg/Collection.svg";
import conceptPageIcon from "../svg/conceptpage.svg";
import fileIcon from "../svg/file.svg";
import frameIcon from "../svg/Frame.svg";
import homeIcon from "../svg/home.svg";
import lightningIcon from "../svg/LightningBoltOutline.svg";
import rawPageIcon from "../svg/Ellipse.svg";
import sideboxIcon from "../svg/sidebox.svg";
import sourcePageIcon from "../svg/source_page.svg";
import sparkleIcon from "../svg/icon.svg";
import settingIcon from "../svg/uil_setting.svg";
import userCircleIcon from "../svg/UserCircle.svg";

type TreeItem = {
  id: string;
  label: string;
  type?: "folder" | "file";
  status?: "uploading" | DocumentStatus;
  errorMessage?: string;
  documentId?: string;
  mimeType?: string;
  byteSize?: number;
  sourceUri?: string;
  uploadedAt?: string;
  active?: boolean;
  children?: TreeItem[];
};

type Project = {
  id: string;
  title: string;
  items: TreeItem[];
};

type DropPosition = "before" | "inside" | "after";

type DropTarget = {
  projectId: string;
  targetId: string;
  position: DropPosition;
};

type ContextMenuState = {
  projectId: string;
  itemId: string;
  x: number;
  y: number;
};

type EditingState = {
  projectId: string;
  itemId: string;
  label: string;
};

type FileDropTarget = {
  projectId: string;
  folderId: string | null;
};

type DocumentStatus = "uploaded" | "processing" | "completed" | "failed";

type DocumentUploadResponse = {
  id: string;
  filename: string;
  mime_type: string;
  byte_size: number;
  status: DocumentStatus;
  source_uri: string;
  uploaded_at: string;
};

type DocumentItemResponse = DocumentUploadResponse & {
  extracted_text_uri?: string;
  processed_at?: string;
  error_message?: string;
};

type DocumentListResponse = {
  documents: DocumentItemResponse[];
};

type WikiGraphNodeResponse = {
  id: string;
  page_type: "source" | "concept" | string;
  title: string;
  slug: string;
  summary?: string;
  status: string;
};

type WikiGraphEdgeResponse = {
  from_page_id: string;
  to_page_id: string;
  link_type: string;
  label?: string | null;
  confidence: number;
};

type WikiGraphResponse = {
  nodes: WikiGraphNodeResponse[];
  edges: WikiGraphEdgeResponse[];
};

type BackendData = {
  documents: DocumentItemResponse[];
  graph: WikiGraphResponse;
};

type GraphNode = {
  id: string;
  label: string;
  size?: number;
  kind?: "source" | "concept" | "raw" | "progress";
  progress?: number;
};

type GraphLink = {
  from: string;
  to: string;
  dashed?: boolean;
  active?: boolean;
};

type SvgAsset = StaticImageData;
type NodePosition = { x: number; y: number };
type NodePositionMap = Record<string, NodePosition>;
type RailView = "home" | "rules" | "logs" | "settings";
type GraphCache = {
  signature: string;
  positions: NodePositionMap;
  pan: NodePosition;
  zoom: number;
};

const GRAPH_WIDTH = 746;
const GRAPH_HEIGHT = 568;
const GRAPH_CACHE_KEY = "fruition.graph.layout.v2";
const NODE_REVEAL_INTERVAL_MS = 180;
const GRAPH_WORLD_MIN_WIDTH = 3200;
const FOCUS_TRANSITION_MS = 500;
const railItems: { id: RailView; label: string; icon: SvgAsset }[] = [
  { id: "home", label: "홈", icon: homeIcon },
  { id: "rules", label: "규칙", icon: lightningIcon },
  { id: "logs", label: "로그", icon: collectionIcon },
  { id: "settings", label: "설정", icon: settingIcon }
];
const GRAPH_CENTER = { x: GRAPH_WIDTH / 2, y: GRAPH_HEIGHT / 2 };
const GRAPH_ZOOM = {
  min: 0.62,
  max: 2.25,
  wheelSensitivity: 0.0018
};
const GRAPH_PHYSICS = {
  centerStrength: 0.0014,
  damping: 0.42,
  settleThreshold: 0.02,
  revealCenterBoost: 1.7,
  revealLinkBoost: 2.8,
  revealDamping: 0.72,
  originStrength: 0,
  repulsionStrength: 0.0025,
  repulsionRange: 145,
  collisionRadiusMultiplier: 0.32,
  nodeDistanceMultiplier: 0.125,
  sourceNodeDistanceMultiplier: 2.5,
  linkStrength: 0.032,
  linkDistanceMultiplier: 0.25,
  linkDistance: {
    source: 178,
    raw: 92,
    progress: 132,
    sourceConcept: 88,
    concept: 98,
    fallback: 118
  }
};

function SvgIcon({ src, className }: { src: SvgAsset; className?: string }) {
  const iconClassName = className || "svg-icon";

  if (src === archiveIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
        <path d="M2.7501 1.87491C2.28597 1.87491 1.84085 2.05928 1.51266 2.38747C1.18447 2.71566 1.0001 3.16078 1.0001 3.62491C1.0001 4.08904 1.18447 4.53416 1.51266 4.86235C1.84085 5.19053 2.28597 5.37491 2.7501 5.37491H13.2501C13.7142 5.37491 14.1593 5.19053 14.4875 4.86235C14.8157 4.53416 15.0001 4.08904 15.0001 3.62491C15.0001 3.16078 14.8157 2.71566 14.4875 2.38747C14.1593 2.05928 13.7142 1.87491 13.2501 1.87491H2.7501Z" fill="currentColor" opacity="0.55" />
        <path fillRule="evenodd" clipRule="evenodd" d="M1.87498 5.1064H14.125V12.1207C14.125 12.6522 13.9406 13.162 13.6124 13.5378C13.2842 13.9137 12.8391 14.1248 12.375 14.1248H3.62498C3.16086 14.1248 2.71574 13.9137 2.38755 13.5378C2.05936 13.162 1.87498 12.6522 1.87498 12.1207V5.1064Z" fill="currentColor" opacity="0.55" />
        <path d="M6.50627 8.25621C6.34217 8.4203 6.24998 8.64286 6.24998 8.87492C6.24998 9.10699 6.34217 9.32955 6.50627 9.49364C6.67036 9.65774 6.89292 9.74992 7.12498 9.74992H8.87498C9.10705 9.74992 9.32961 9.65774 9.4937 9.49364C9.6578 9.32955 9.74998 9.10699 9.74998 8.87492C9.74998 8.64286 9.6578 8.4203 9.4937 8.25621C9.32961 8.09211 9.10705 7.99992 8.87498 7.99992H7.12498C6.89292 7.99992 6.67036 8.09211 6.50627 8.25621Z" fill="currentColor" />
        <rect x="1.87498" y="5.15607" width="12.2499" height="0.796814" fill="currentColor" />
      </svg>
    );
  }

  if (src === collectionIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M7.6875 2.9375C7.37256 2.9375 7.07051 3.06261 6.84781 3.28531C6.62511 3.50801 6.5 3.81006 6.5 4.125C6.5 4.43994 6.62511 4.74199 6.84781 4.96469C7.07051 5.18739 7.37256 5.3125 7.6875 5.3125H14.8125C15.1274 5.3125 15.4295 5.18739 15.6522 4.96469C15.8749 4.74199 16 4.43994 16 4.125C16 3.81006 15.8749 3.50801 15.6522 3.28531C15.4295 3.06261 15.1274 2.9375 14.8125 2.9375H7.6875ZM4.125 7.6875C4.125 7.37256 4.25011 7.07051 4.47281 6.84781C4.69551 6.62511 4.99756 6.5 5.3125 6.5H17.1875C17.5024 6.5 17.8045 6.62511 18.0272 6.84781C18.2499 7.07051 18.375 7.37256 18.375 7.6875C18.375 8.00244 18.2499 8.30449 18.0272 8.52719C17.8045 8.74989 17.5024 8.875 17.1875 8.875H5.3125C4.99756 8.875 4.69551 8.74989 4.47281 8.52719C4.25011 8.30449 4.125 8.00244 4.125 7.6875ZM1.75 12.4375C1.75 11.8076 2.00022 11.2035 2.44562 10.7581C2.89102 10.3127 3.49511 10.0625 4.125 10.0625H18.375C19.0049 10.0625 19.609 10.3127 20.0544 10.7581C20.4998 11.2035 20.75 11.8076 20.75 12.4375V17.1875C20.75 17.8174 20.4998 18.4215 20.0544 18.8669C19.609 19.3123 19.0049 19.5625 18.375 19.5625H4.125C3.49511 19.5625 2.89102 19.3123 2.44562 18.8669C2.00022 18.4215 1.75 17.8174 1.75 17.1875V12.4375Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === rawPageIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 10 10" fill="none">
        <circle cx="5" cy="5" r="4.5" fill="currentColor" opacity="0.12" stroke="currentColor" strokeDasharray="2 1.67" />
      </svg>
    );
  }

  if (src === frameIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 18 18" fill="none">
        <path d="M9 13V5M13 9L9 5L5 9" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === lightningIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M12.1875 9.375V2.8125L3.74998 13.125H10.3125V19.6875L18.75 9.375H12.1875Z" fill="currentColor" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === userCircleIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 28 28" fill="none">
        <path fillRule="evenodd" clipRule="evenodd" d="M27.7667 13.8833C27.7667 17.5654 26.304 21.0967 23.7003 23.7003C21.0967 26.304 17.5654 27.7667 13.8833 27.7667C10.2012 27.7667 6.66996 26.304 4.06633 23.7003C1.4627 21.0967 0 17.5654 0 13.8833C0 10.2012 1.4627 6.66996 4.06633 4.06633C6.66996 1.4627 10.2012 0 13.8833 0C17.5654 0 21.0967 1.4627 23.7003 4.06633C26.304 6.66996 27.7667 10.2012 27.7667 13.8833Z" fill="currentColor" opacity="0.55" />
        <path d="M16.999 13.2058C17.7679 12.4369 18.1999 11.394 18.1999 10.3067C18.1999 9.21926 17.7679 8.17641 16.999 7.40751C16.2301 6.63861 15.1873 6.20665 14.0999 6.20665C13.0125 6.20665 11.9696 6.63861 11.2007 7.40751C10.4318 8.17641 9.99988 9.21926 9.99988 10.3067C9.99988 11.394 10.4318 12.4369 11.2007 13.2058C11.9696 13.9747 13.0125 14.4067 14.0999 14.4067C15.1873 14.4067 16.2301 13.9747 16.999 13.2058Z" fill="currentColor" />
        <path d="M8.65361 18.0829C10.249 17.049 12.1047 16.4997 14.0001 16.5001C15.8955 16.4997 17.7512 17.049 19.3466 18.0829C20.5706 18.876 21.602 19.6344 22.3726 20.7306C22.8182 21.3644 22.6896 22.2199 22.1277 22.7533C21.1937 23.6397 20.1216 24.3692 18.9516 24.911C17.3982 25.6304 15.709 26.0019 14.0001 26C12.2912 26.0019 10.6021 25.6304 9.04863 24.911C7.87868 24.3692 6.80652 23.6397 5.87259 22.7533C5.3106 22.2199 5.18207 21.3644 5.62768 20.7306C6.39828 19.6344 7.4296 18.876 8.65361 18.0829Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === conceptPageIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 10 10" fill="none">
        <circle cx="5" cy="5" r="5" fill="currentColor" />
      </svg>
    );
  }

  if (src === fileIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 18 18" fill="none">
        <path d="M10.25 2.75H5.25C4.91848 2.75 4.60054 2.8817 4.36612 3.11612C4.1317 3.35054 4 3.66848 4 4V14C4 14.3315 4.1317 14.6495 4.36612 14.8839C4.60054 15.1183 4.91848 15.25 5.25 15.25H12.75C13.0815 15.25 13.3995 15.1183 13.6339 14.8839C13.8683 14.6495 14 14.3315 14 14V6.5L10.25 2.75Z" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M10.25 6.5V2.75L14 6.5H10.25Z" fill="currentColor" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === homeIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M11.25 2.53125C11.4829 2.53125 11.6832 2.60792 11.8174 2.71191L18.9316 10.0156C19.5965 10.6983 19.9687 11.6134 19.9688 12.5664V19.4844C19.9687 19.558 19.9348 19.6677 19.8057 19.7754C19.6719 19.8868 19.4623 19.9687 19.2188 19.9688H16.0312C15.7877 19.9688 15.5781 19.8868 15.4443 19.7754C15.3152 19.6677 15.2813 19.558 15.2812 19.4844V16.8281C15.2812 16.1973 14.9792 15.6307 14.5107 15.2402C14.0468 14.8537 13.4454 14.6563 12.8438 14.6562H9.65625C9.0546 14.6562 8.45324 14.8537 7.98926 15.2402C7.52075 15.6307 7.21875 16.1973 7.21875 16.8281V19.4844C7.2187 19.558 7.18483 19.6678 7.05566 19.7754C6.92191 19.8868 6.71235 19.9688 6.46875 19.9688H3.28125C3.03765 19.9687 2.82809 19.8868 2.69434 19.7754C2.56517 19.6678 2.5313 19.558 2.53125 19.4844V12.5664C2.53126 11.6134 2.90349 10.6983 3.56836 10.0156L10.6816 2.71191C10.8158 2.60767 11.0167 2.53125 11.25 2.53125Z" fill="currentColor" stroke="currentColor" strokeWidth="1.6875" />
      </svg>
    );
  }

  if (src === sparkleIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
        <path d="M4.64443 0.327192C5.26616 0.175049 5.8942 0.55616 6.04677 1.17778L6.79677 4.24223L11.7645 1.39164C12.3199 1.07278 13.0297 1.26499 13.3485 1.82036C13.667 2.37564 13.475 3.0846 12.9198 3.40336L9.1288 5.57914L11.9257 6.49028C12.5345 6.68871 12.8672 7.34432 12.6688 7.95317C12.4703 8.56169 11.8156 8.89451 11.2069 8.69633L7.60048 7.52055L9.25478 14.2706C9.40688 14.8922 9.02657 15.5192 8.40517 15.6719C7.78329 15.8243 7.15441 15.4441 7.00185 14.8223L5.27821 7.78911L2.19423 9.56157C1.63901 9.88012 0.930117 9.68787 0.611222 9.13286C0.292662 8.57764 0.483935 7.86875 1.03896 7.54985L3.44521 6.16801L1.75185 5.61625C1.14328 5.4178 0.810602 4.76303 1.00868 4.15434C1.20707 3.54566 1.86185 3.2121 2.4706 3.4102L4.35536 4.02348L3.79384 1.72954C3.64174 1.10777 4.02273 0.479682 4.64443 0.327192Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === sideboxIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 20 20" fill="none">
        <rect x="4" y="4.5" width="12" height="11" rx="1.81333" stroke="currentColor" />
        <path d="M10.9558 4.50054L10.9558 6.75116C10.9558 6.91028 11.0191 7.06286 11.1316 7.17536L12.58 8.62376C12.6925 8.73626 12.7558 8.88884 12.7558 9.04796L12.7558 10.9512C12.7558 11.1103 12.6925 11.2629 12.58 11.3754L11.1316 12.8238C11.0191 12.9363 10.9558 13.0888 10.9558 13.248L10.9558 15.4998" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === sourcePageIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 10 10" fill="none">
        <circle cx="5" cy="5" r="5" fill="currentColor" />
      </svg>
    );
  }

  if (src === settingIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M18.4046 13.3636L19.25 14.3147L17.8973 16.6608L16.6503 16.4072C15.8891 16.2516 15.0974 16.3809 14.4253 16.7705C13.7532 17.1601 13.2475 17.783 13.0043 18.5208L12.6027 19.7044H9.89729L9.51684 18.4997C9.27362 17.7618 8.76797 17.139 8.09587 16.7494C7.42377 16.3598 6.632 16.2305 5.87087 16.3861L4.62384 16.6397L3.25 14.3042L4.09544 13.353C4.61534 12.7718 4.90277 12.0193 4.90277 11.2394C4.90277 10.4596 4.61534 9.70708 4.09544 9.12581L3.25 8.17469L4.60271 5.84972L5.84974 6.10336C6.61087 6.25894 7.40263 6.12965 8.07473 5.74003C8.74684 5.3504 9.25249 4.72757 9.49571 3.98975L9.89729 2.79556H12.6027L13.0043 4.00032C13.2475 4.73813 13.7532 5.36097 14.4253 5.75059C15.0974 6.14022 15.8891 6.26951 16.6503 6.11393L17.8973 5.86029L19.25 8.2064L18.4046 9.15752C17.8905 9.73745 17.6066 10.4856 17.6066 11.2606C17.6066 12.0355 17.8905 12.7837 18.4046 13.3636Z" fill="currentColor" stroke="currentColor" strokeWidth="2.4" opacity="0.72" />
        <circle cx="11.2498" cy="11.2502" r="3.1" fill="currentColor" stroke="currentColor" />
      </svg>
    );
  }

  return null;
}

const initialProjects: Project[] = [
  {
    id: "project-uploaded-documents",
    title: "업로드 문서",
    items: []
  }
];

const nodes: GraphNode[] = [
  { id: "core", label: "통합 교육 효과 분석", size: 32, kind: "source" },
  { id: "peer", label: "또래 관계 종단 연구", size: 26, kind: "source" },
  { id: "emotion", label: "정서 발달 보고서", size: 26, kind: "source" },
  { id: "family", label: "가족 지원 프로그램", size: 26, kind: "source" },
  { id: "teacher", label: "교사 인식 설문", size: 26, kind: "source" },
  { id: "case", label: "학습지원 사례집", size: 31, kind: "progress", progress: 58 },
  { id: "raw1", label: "또래관계_연구.pdf", kind: "raw" },
  { id: "raw2", label: "정서발달_보고서.pdf", kind: "raw" },
  { id: "raw3", label: "통합교육_효과분석.docx", kind: "raw" },
  { id: "raw4", label: "가족지원_프로그램.pdf", kind: "raw" },
  { id: "raw5", label: "교사인식_설문.docx", kind: "raw" },
  { id: "accept", label: "또래 수용" },
  { id: "mediation", label: "또래 매개 중재" },
  { id: "social", label: "사회적 기술" },
  { id: "concept", label: "자아 개념" },
  { id: "regulation", label: "정서 조절" },
  { id: "esteem", label: "자기 효능감" },
  { id: "socialdev", label: "사회성 발달" },
  { id: "parent", label: "부모 참여" },
  { id: "home", label: "가정 연계" },
  { id: "support", label: "정서 지원" },
  { id: "coop", label: "협력 교수" },
  { id: "operation", label: "통합학급 운영" },
  { id: "environment", label: "통합 환경" },
  { id: "training", label: "교사 연수" },
  { id: "attitude", label: "양육 태도" },
  { id: "belonging", label: "소속감" },
  { id: "curriculum", label: "개별화 교육계획" },
  { id: "goal", label: "교육과정 수정" },
  { id: "confidence", label: "교사 효능감" },
  { id: "autonomy", label: "자존감" }
];

const GRAPH_SIGNATURE = `random-layout-v2:${nodes.map((node) => node.id).sort().join("|")}`;

const links: GraphLink[] = [
  { from: "core", to: "peer" },
  { from: "core", to: "emotion", active: true },
  { from: "core", to: "family" },
  { from: "core", to: "teacher", active: true },
  { from: "core", to: "case", dashed: true, active: true },
  { from: "peer", to: "raw1", dashed: true },
  { from: "emotion", to: "raw2", dashed: true },
  { from: "core", to: "raw3", dashed: true },
  { from: "family", to: "raw4", dashed: true },
  { from: "teacher", to: "raw5", dashed: true },
  { from: "peer", to: "accept" },
  { from: "peer", to: "mediation" },
  { from: "peer", to: "social" },
  { from: "family", to: "parent" },
  { from: "family", to: "home" },
  { from: "family", to: "socialdev" },
  { from: "family", to: "attitude" },
  { from: "emotion", to: "concept" },
  { from: "emotion", to: "regulation" },
  { from: "emotion", to: "autonomy" },
  { from: "core", to: "esteem" },
  { from: "core", to: "socialdev" },
  { from: "core", to: "support" },
  { from: "core", to: "coop" },
  { from: "core", to: "operation" },
  { from: "core", to: "environment" },
  { from: "core", to: "curriculum" },
  { from: "core", to: "goal" },
  { from: "teacher", to: "training" },
  { from: "teacher", to: "confidence" }
];

const nodeById = new Map(nodes.map((node) => [node.id, node]));

const nodeDegrees = nodes.reduce<Record<string, number>>((degrees, node) => {
  degrees[node.id] = links.filter((link) => link.from === node.id || link.to === node.id).length;
  return degrees;
}, {});

const nodeSizes = nodes.reduce<Record<string, number>>((sizes, node) => {
  const degree = nodeDegrees[node.id] ?? 0;
  if (node.kind === "raw") sizes[node.id] = Math.min(26, 16 + degree * 4);
  else if (node.kind === "source") sizes[node.id] = Math.min(44, 22 + degree * 3.2);
  else if (node.kind === "progress") sizes[node.id] = Math.min(38, 22 + degree * 4);
  else sizes[node.id] = Math.min(32, 15 + degree * 3.5);
  return sizes;
}, {});

const nodePairs = nodes.flatMap((nodeA, index) =>
  nodes.slice(index + 1).map((nodeB) => ({ nodeA, nodeB }))
);

function linkKey(nodeAId: string, nodeBId: string) {
  return nodeAId < nodeBId ? `${nodeAId}:${nodeBId}` : `${nodeBId}:${nodeAId}`;
}

const linkedNodePairs = new Set(links.map((link) => linkKey(link.from, link.to)));

function areNodesLinked(nodeAId: string, nodeBId: string) {
  return linkedNodePairs.has(linkKey(nodeAId, nodeBId));
}

function isRawSourceLink(link: GraphLink) {
  const from = nodeById.get(link.from);
  const to = nodeById.get(link.to);
  const kinds = [from?.kind, to?.kind];
  return kinds.includes("raw") && kinds.includes("source");
}

function graphNodeKind(node: GraphNode) {
  return node.kind ?? "concept";
}

function nodeSize(node: GraphNode) {
  return nodeSizes[node.id] ?? 20;
}

function physicsNodeRadius(node: GraphNode) {
  return (nodeSize(node) / 2) * GRAPH_PHYSICS.collisionRadiusMultiplier;
}

function idealLinkDistanceValue(link: GraphLink) {
  const from = nodes.find((node) => node.id === link.from);
  const to = nodes.find((node) => node.id === link.to);
  if (!from || !to) return GRAPH_PHYSICS.linkDistance.fallback * GRAPH_PHYSICS.linkDistanceMultiplier;

  const kinds = [graphNodeKind(from), graphNodeKind(to)];
  let distance = GRAPH_PHYSICS.linkDistance.concept;
  if (kinds.every((kind) => kind === "source")) distance = GRAPH_PHYSICS.linkDistance.source;
  else if (kinds.includes("raw")) distance = GRAPH_PHYSICS.linkDistance.raw;
  else if (kinds.includes("progress")) distance = GRAPH_PHYSICS.linkDistance.progress;
  else if (kinds.includes("source") && kinds.includes("concept")) distance = GRAPH_PHYSICS.linkDistance.sourceConcept;
  return distance * GRAPH_PHYSICS.linkDistanceMultiplier;
}

const linkForces = links.map((link) => ({
  ...link,
  idealDistance: idealLinkDistanceValue(link),
  weight: 1 + Math.min(0.85, ((nodeDegrees[link.from] ?? 0) + (nodeDegrees[link.to] ?? 0)) * 0.035)
}));

function pairDistanceValue(nodeA: GraphNode, nodeB: GraphNode) {
  if (areNodesLinked(nodeA.id, nodeB.id)) return 0;

  const base = physicsNodeRadius(nodeA) + physicsNodeRadius(nodeB);
  const kindA = graphNodeKind(nodeA);
  const kindB = graphNodeKind(nodeB);
  let distance = base + 48;
  if (kindA === "source" && kindB === "source") distance = base + 118;
  else if (kindA === "source" || kindB === "source") distance = base + 58;
  else if (kindA === "raw" || kindB === "raw") distance = base + 42;
  const typeMultiplier = kindA === "source" && kindB === "source"
    ? GRAPH_PHYSICS.sourceNodeDistanceMultiplier
    : 1;
  return distance * GRAPH_PHYSICS.nodeDistanceMultiplier * typeMultiplier;
}

const pairForces = nodePairs.map(({ nodeA, nodeB }) => ({
  nodeA,
  nodeB,
  linked: areNodesLinked(nodeA.id, nodeB.id),
  desiredDistance: pairDistanceValue(nodeA, nodeB),
  minDistance: physicsNodeRadius(nodeA) + physicsNodeRadius(nodeB)
}));

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function createRandomNodePositions() {
  const outerRadiusX = GRAPH_WIDTH * 0.18;
  const outerRadiusY = GRAPH_HEIGHT * 0.17;
  const innerRadiusX = GRAPH_WIDTH * 0.08;
  const innerRadiusY = GRAPH_HEIGHT * 0.075;

  return Object.fromEntries(
    nodes.map((node) => {
      if (node.id === "core") return [node.id, GRAPH_CENTER];

      const isPrimaryNode = node.kind === "source" || node.kind === "progress";
      const angle = randomBetween(0, Math.PI * 2);
      const ringIndex = isPrimaryNode || Math.random() > 0.58 ? 0 : 1;
      const radiusX = ringIndex === 0 ? outerRadiusX : innerRadiusX;
      const radiusY = ringIndex === 0 ? outerRadiusY : innerRadiusY;
      const jitterX = randomBetween(-22, 22);
      const jitterY = randomBetween(-18, 18);

      return [node.id, {
        x: GRAPH_CENTER.x + Math.cos(angle) * radiusX + jitterX,
        y: GRAPH_CENTER.y + Math.sin(angle) * radiusY + jitterY
      }];
    })
  );
}

const initialNodePositions: NodePositionMap = createRandomNodePositions();

function readGraphCache() {
  if (typeof window === "undefined") return null;

  try {
    const rawCache = window.localStorage.getItem(GRAPH_CACHE_KEY);
    if (!rawCache) return null;

    const cache = JSON.parse(rawCache) as Partial<GraphCache>;
    if (cache.signature !== GRAPH_SIGNATURE || !cache.positions || !cache.pan || typeof cache.zoom !== "number") {
      return null;
    }

    const hasEveryNode = nodes.every((node) => {
      const position = cache.positions?.[node.id];
      return typeof position?.x === "number" && typeof position?.y === "number";
    });

    return hasEveryNode ? cache as GraphCache : null;
  } catch {
    return null;
  }
}

function cachedOrInitialPositions() {
  return readGraphCache()?.positions ?? initialNodePositions;
}

function cachedOrInitialPan() {
  return readGraphCache()?.pan ?? { x: 0, y: 0 };
}

function cachedOrInitialZoom() {
  return readGraphCache()?.zoom ?? 1;
}

function itemContainsId(item: TreeItem, itemId: string): boolean {
  if (item.id === itemId) return true;
  return item.children?.some((child) => itemContainsId(child, itemId)) ?? false;
}

function isFileItem(item: TreeItem) {
  return item.type === "file";
}

function isSupportedUploadFile(file: File) {
  const name = file.name.toLowerCase();
  return name.endsWith(".pdf") || name.endsWith(".md");
}

function getDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return Array.from(event.dataTransfer.files).filter(isSupportedUploadFile);
}

function hasDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return event.dataTransfer.types.includes("Files");
}

function createClientId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function removeTreeItem(items: TreeItem[], itemId: string): { items: TreeItem[]; removed: TreeItem | null } {
  let removed: TreeItem | null = null;
  const nextItems: TreeItem[] = [];

  for (const item of items) {
    if (item.id === itemId) {
      removed = item;
      continue;
    }

    if (item.children?.length) {
      const result = removeTreeItem(item.children, itemId);
      if (result.removed) {
        removed = result.removed;
        nextItems.push({ ...item, children: result.items });
        continue;
      }
    }

    nextItems.push(item);
  }

  return { items: nextItems, removed };
}

function insertTreeItem(items: TreeItem[], movedItem: TreeItem, target: DropTarget): TreeItem[] {
  return items.flatMap((item) => {
    if (item.id === target.targetId) {
      if (target.position === "before") return [movedItem, item];
      if (target.position === "after") return [item, movedItem];
      return [{ ...item, children: [...(item.children ?? []), movedItem] }];
    }

    if (item.children?.length) {
      return [{ ...item, children: insertTreeItem(item.children, movedItem, target) }];
    }

    return [item];
  });
}

function moveTreeItem(items: TreeItem[], itemId: string, target: DropTarget): TreeItem[] {
  const result = removeTreeItem(items, itemId);
  if (!result.removed) return items;
  if (result.removed.id === target.targetId || itemContainsId(result.removed, target.targetId)) return items;
  return insertTreeItem(result.items, result.removed, target);
}

function updateTreeItemLabel(items: TreeItem[], itemId: string, label: string): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return { ...item, label };
    if (item.children?.length) return { ...item, children: updateTreeItemLabel(item.children, itemId, label) };
    return item;
  });
}

function findTreeItem(items: TreeItem[], itemId: string): TreeItem | null {
  for (const item of items) {
    if (item.id === itemId) return item;
    const found = item.children ? findTreeItem(item.children, itemId) : null;
    if (found) return found;
  }
  return null;
}

function appendItemsToFolder(items: TreeItem[], folderId: string | null, nextItems: TreeItem[]): TreeItem[] {
  if (!folderId) return [...items, ...nextItems];

  return items.map((item) => {
    if (item.id === folderId) {
      return { ...item, children: [...(item.children ?? []), ...nextItems] };
    }
    if (item.children?.length) return { ...item, children: appendItemsToFolder(item.children, folderId, nextItems) };
    return item;
  });
}

function updateTreeItemStatus(items: TreeItem[], itemId: string, status: TreeItem["status"], errorMessage?: string): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return { ...item, status, errorMessage };
    if (item.children?.length) return { ...item, children: updateTreeItemStatus(item.children, itemId, status, errorMessage) };
    return item;
  });
}

function applyUploadedDocument(items: TreeItem[], itemId: string, document: DocumentUploadResponse): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) {
      return {
        ...item,
        label: document.filename,
        status: document.status,
        errorMessage: undefined,
        documentId: document.id,
        mimeType: document.mime_type,
        byteSize: document.byte_size,
        sourceUri: document.source_uri,
        uploadedAt: document.uploaded_at
      };
    }
    if (item.children?.length) return { ...item, children: applyUploadedDocument(item.children, itemId, document) };
    return item;
  });
}

async function uploadDocumentFile(file: File) {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch("/api/documents", {
    method: "POST",
    body: formData
  });

  if (!response.ok) {
    let message = "문서 업로드에 실패했습니다.";
    try {
      const body = await response.json();
      message = body?.error?.message || message;
    } catch {
      // JSON 오류 본문이 없으면 기본 메시지를 유지합니다.
    }
    throw new Error(message);
  }

  return response.json() as Promise<DocumentUploadResponse>;
}

async function fetchBackendData(): Promise<BackendData> {
  const [documentsResponse, graphResponse] = await Promise.all([
    fetch("/api/documents", { cache: "no-store" }),
    fetch("/api/wiki/graph", { cache: "no-store" })
  ]);

  if (!documentsResponse.ok) throw new Error("문서 목록을 불러오지 못했습니다.");
  if (!graphResponse.ok) throw new Error("Wiki graph를 불러오지 못했습니다.");

  const documents = await documentsResponse.json() as DocumentListResponse;
  const graph = await graphResponse.json() as WikiGraphResponse;
  return { documents: documents.documents ?? [], graph };
}

function buildGraphFromBackend(documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const graphNodes: GraphNode[] = (graph.nodes ?? []).map((node) => ({
    id: node.id,
    label: node.title || node.slug || node.id,
    kind: node.page_type === "source" ? "source" : "concept",
    size: node.page_type === "source" ? 32 : undefined
  }));

  const graphLinks: GraphLink[] = (graph.edges ?? []).map((edge) => ({
    from: edge.from_page_id,
    to: edge.to_page_id,
    active: edge.link_type === "source_mentions_concept"
  }));

  const graphNodeIds = new Set(graphNodes.map((node) => node.id));
  const sourceDocumentIds = new Set(
    graphNodes
      .filter((node) => node.kind === "source" && node.id.startsWith("source:"))
      .map((node) => node.id.replace("source:", ""))
  );

  const documentNodes = documents
    .filter((document) => !sourceDocumentIds.has(document.id))
    .map((document) => {
      const progress = document.status === "completed" ? 100 : document.status === "failed" ? 0 : 58;
      return {
        id: `document:${document.id}`,
        label: document.filename,
        kind: document.status === "failed" ? "raw" as const : "progress" as const,
        progress
      };
    })
    .filter((node) => !graphNodeIds.has(node.id));

  return {
    nodes: [...graphNodes, ...documentNodes],
    links: graphLinks
  };
}

function syncDocumentItems(items: TreeItem[], documents: DocumentItemResponse[]): TreeItem[] {
  const documentById = new Map(documents.map((document) => [document.id, document]));
  return items.map((item) => {
    const document = item.documentId ? documentById.get(item.documentId) : null;
    const nextItem = document ? {
      ...item,
      label: document.filename,
      status: document.status,
      errorMessage: document.error_message,
      mimeType: document.mime_type,
      byteSize: document.byte_size,
      sourceUri: document.source_uri,
      uploadedAt: document.uploaded_at
    } : item;
    if (nextItem.children?.length) return { ...nextItem, children: syncDocumentItems(nextItem.children, documents) };
    return nextItem;
  });
}

function collectDocumentIds(items: TreeItem[], ids = new Set<string>()) {
  for (const item of items) {
    if (item.documentId) ids.add(item.documentId);
    if (item.children?.length) collectDocumentIds(item.children, ids);
  }
  return ids;
}

function mergeBackendDocumentsIntoProjects(projects: Project[], documents: DocumentItemResponse[]) {
  const knownDocumentIds = collectDocumentIds(projects.flatMap((project) => project.items));
  const missingDocuments = documents.filter((document) => !knownDocumentIds.has(document.id));
  if (missingDocuments.length === 0) {
    return projects.map((project) => ({ ...project, items: syncDocumentItems(project.items, documents) }));
  }

  const backendItems = missingDocuments.map((document) => ({
    id: `document-file-${document.id}`,
    label: document.filename,
    type: "file" as const,
    status: document.status,
    documentId: document.id,
    mimeType: document.mime_type,
    byteSize: document.byte_size,
    sourceUri: document.source_uri,
    uploadedAt: document.uploaded_at,
    errorMessage: document.error_message
  }));

  return projects.map((project, index) => {
    const syncedItems = syncDocumentItems(project.items, documents);
    if (index !== 0) return { ...project, items: syncedItems };
    return { ...project, items: [...syncedItems, ...backendItems] };
  });
}

function resolveDropPosition(event: ReactDragEvent<HTMLButtonElement>): DropPosition {
  const rect = event.currentTarget.getBoundingClientRect();
  const offsetY = event.clientY - rect.top;
  if (offsetY < rect.height * 0.28) return "before";
  if (offsetY > rect.height * 0.72) return "after";
  return "inside";
}

function setLightDragPreview(event: ReactDragEvent<HTMLButtonElement>) {
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

function TreeNode({ item, depth, openIds, onToggle, projectId, draggedItemId, dropTarget, fileDropTarget, editing, onDragStart, onDragOverItem, onFileDragOver, onFileDragLeave, onDropItem, onDropFiles, onDragEnd, onContextMenuItem, onEditingChange, onCommitEditing, onCancelEditing }: {
  item: TreeItem;
  depth: number;
  openIds: Set<string>;
  onToggle: (id: string) => void;
  projectId: string;
  draggedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDropItem: (target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
}) {
  const hasChildren = Boolean(item.children?.length);
  const isOpen = openIds.has(item.id);
  const Icon = hasChildren ? (isOpen ? ChevronDown : ChevronRight) : Folder;
  const isDropTarget = dropTarget?.projectId === projectId && dropTarget.targetId === item.id;
  const isFileDropTarget = fileDropTarget?.projectId === projectId && fileDropTarget.folderId === item.id;
  const isEditing = editing?.projectId === projectId && editing.itemId === item.id;
  const canNestChildren = !isFileItem(item);

  function handleEditingKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") onCommitEditing();
    if (event.key === "Escape") onCancelEditing();
  }

  return (
    <>
      <button
        type="button"
        className={[
          "tree-row",
          item.active ? "is-active" : "",
          draggedItemId === item.id ? "is-dragging" : "",
          isFileDropTarget ? "is-file-drop-target" : "",
          isDropTarget ? `is-drop-${dropTarget.position}` : ""
        ].filter(Boolean).join(" ")}
        style={{ paddingLeft: 10 + depth * 17 }}
        title={item.errorMessage ?? item.sourceUri}
        aria-expanded={hasChildren ? isOpen : undefined}
        draggable={!isEditing && !isFileItem(item)}
        onDragStart={(event) => {
          if (isFileItem(item)) return;
          event.dataTransfer.effectAllowed = "move";
          event.dataTransfer.setData("text/plain", item.id);
          setLightDragPreview(event);
          onDragStart(projectId, item.id);
        }}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = hasDroppedFiles(event) ? "copy" : "move";
          if (hasDroppedFiles(event) && !isFileItem(item)) {
            event.stopPropagation();
            onFileDragOver({ projectId, folderId: item.id });
            return;
          }
          if (isFileItem(item)) return;
          const position = resolveDropPosition(event);
          onDragOverItem({ projectId, targetId: item.id, position });
        }}
        onDragLeave={(event) => {
          if (!hasDroppedFiles(event)) return;
          event.stopPropagation();
          const nextTarget = event.relatedTarget;
          if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
          onFileDragLeave();
        }}
        onDrop={(event) => {
          event.preventDefault();
          if (hasDroppedFiles(event)) {
            event.stopPropagation();
            onFileDragLeave();
            onDropFiles(projectId, canNestChildren ? item.id : null, getDroppedFiles(event));
            return;
          }
          if (!isFileItem(item)) {
            onDropItem({ projectId, targetId: item.id, position: resolveDropPosition(event) });
          }
        }}
        onDragEnd={onDragEnd}
        onContextMenu={(event) => onContextMenuItem(event, projectId, item.id)}
        onClick={() => {
          if (!isEditing && hasChildren) onToggle(item.id);
        }}
      >
        {isFileItem(item) ? (
          <SvgIcon src={fileIcon} className="tree-asset" />
        ) : hasChildren ? (
          <Icon size={14} />
        ) : (
          <SvgIcon src={archiveIcon} className="tree-asset" />
        )}
        {isEditing ? (
          <input
            className="tree-edit-input"
            value={editing.label}
            autoFocus
            onChange={(event) => onEditingChange(event.target.value)}
            onBlur={onCommitEditing}
            onClick={(event) => event.stopPropagation()}
            onKeyDown={handleEditingKeyDown}
          />
        ) : (
          <>
            <span>{item.label}</span>
            {isFileDropTarget && <small className="tree-drop-hint">여기에 추가</small>}
            {item.status && <small className={`tree-status ${item.status}`}>{item.status}</small>}
          </>
        )}
      </button>
      {hasChildren && isOpen && item.children?.map((child) => (
        <TreeNode
          key={child.id}
          item={child}
          depth={depth + 1}
          openIds={openIds}
          onToggle={onToggle}
          projectId={projectId}
          draggedItemId={draggedItemId}
          dropTarget={dropTarget}
          fileDropTarget={fileDropTarget}
          editing={editing}
          onDragStart={onDragStart}
          onDragOverItem={onDragOverItem}
          onFileDragOver={onFileDragOver}
          onFileDragLeave={onFileDragLeave}
          onDropItem={onDropItem}
          onDropFiles={onDropFiles}
          onDragEnd={onDragEnd}
          onContextMenuItem={onContextMenuItem}
          onEditingChange={onEditingChange}
          onCommitEditing={onCommitEditing}
          onCancelEditing={onCancelEditing}
        />
      ))}
    </>
  );
}

function SidebarTree({ items, projectId, draggedItemId, dropTarget, fileDropTarget, editing, onMoveItem, onDropFiles, onDragStart, onDragOverItem, onFileDragOver, onFileDragLeave, onDragEnd, onContextMenuItem, onEditingChange, onCommitEditing, onCancelEditing, defaultOpenIds = [] }: {
  items: TreeItem[];
  projectId: string;
  draggedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  onMoveItem: (projectId: string, itemId: string, target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
  defaultOpenIds?: string[];
}) {
  const [openIds, setOpenIds] = useState(() => new Set(defaultOpenIds));

  function toggleNode(id: string) {
    setOpenIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function handleDropItem(target: DropTarget) {
    if (!draggedItemId) return;
    onMoveItem(projectId, draggedItemId, target);
  }

  return (
    <>
      {items.map((item) => (
        <TreeNode
          key={item.id}
          item={item}
          depth={0}
          openIds={openIds}
          onToggle={toggleNode}
          projectId={projectId}
          draggedItemId={draggedItemId}
          dropTarget={dropTarget}
          fileDropTarget={fileDropTarget}
          editing={editing}
          onDragStart={onDragStart}
          onDragOverItem={(target) => {
            onDragOverItem(target);
          }}
          onFileDragOver={onFileDragOver}
          onFileDragLeave={onFileDragLeave}
          onDropItem={handleDropItem}
          onDropFiles={onDropFiles}
          onDragEnd={onDragEnd}
          onContextMenuItem={onContextMenuItem}
          onEditingChange={onEditingChange}
          onCommitEditing={onCommitEditing}
          onCancelEditing={onCancelEditing}
        />
      ))}
    </>
  );
}

function ProjectSection({
  project,
  onAddFolder,
  draggedItemId,
  dropTarget,
  fileDropTarget,
  editing,
  onMoveItem,
  onDropFiles,
  onDragStart,
  onDragOverItem,
  onFileDragOver,
  onFileDragLeave,
  onDragEnd,
  onContextMenuItem,
  onEditingChange,
  onCommitEditing,
  onCancelEditing
}: {
  project: Project;
  onAddFolder: (projectId: string) => void;
  draggedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  onMoveItem: (projectId: string, itemId: string, target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
}) {
  const [isOpen, setIsOpen] = useState(true);
  const isRootFileDropTarget = fileDropTarget?.projectId === project.id && fileDropTarget.folderId === null;

  return (
    <section
      className={`project-section ${isRootFileDropTarget ? "is-file-drop-target" : ""}`}
      onDragOver={(event) => {
        if (!hasDroppedFiles(event)) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
        onFileDragOver({ projectId: project.id, folderId: null });
      }}
      onDragLeave={(event) => {
        if (!hasDroppedFiles(event)) return;
        const nextTarget = event.relatedTarget;
        if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
        onFileDragLeave();
      }}
      onDrop={(event) => {
        if (!hasDroppedFiles(event)) return;
        event.preventDefault();
        onFileDragLeave();
        onDropFiles(project.id, null, getDroppedFiles(event));
      }}
    >
      <div className="project-title">
        <button
          type="button"
          className="project-toggle"
          aria-expanded={isOpen}
          onClick={() => setIsOpen((open) => !open)}
        >
          <span>{project.title}</span>
          {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </button>
        <button
          type="button"
          className="project-add-folder"
          aria-label={`${project.title}에 폴더 추가`}
          onClick={() => onAddFolder(project.id)}
        >
          <Plus size={14} />
        </button>
      </div>
      {isOpen && (
        project.items.length > 0
          ? (
            <SidebarTree
              items={project.items}
              projectId={project.id}
              draggedItemId={draggedItemId}
              dropTarget={dropTarget}
              fileDropTarget={fileDropTarget}
              editing={editing}
              onMoveItem={onMoveItem}
              onDropFiles={onDropFiles}
              onDragStart={onDragStart}
              onDragOverItem={onDragOverItem}
              onFileDragOver={onFileDragOver}
              onFileDragLeave={onFileDragLeave}
              onDragEnd={onDragEnd}
              onContextMenuItem={onContextMenuItem}
              onEditingChange={onEditingChange}
              onCommitEditing={onCommitEditing}
              onCancelEditing={onCancelEditing}
            />
          )
          : <p className="project-empty">폴더가 없습니다.</p>
      )}
    </section>
  );
}

function Graph({ nodes = [], links = [], loading = false }: {
  nodes: GraphNode[];
  links: GraphLink[];
  loading?: boolean;
}) {
  const graphSignature = useMemo(
    () => `api-layout-v1:${nodes.map((node) => node.id).sort().join("|")}`,
    [nodes]
  );
  const nodeById = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes]);
  const nodeDegrees = useMemo(() => nodes.reduce<Record<string, number>>((degrees, node) => {
    degrees[node.id] = links.filter((link) => link.from === node.id || link.to === node.id).length;
    return degrees;
  }, {}), [links, nodes]);
  const nodeSizes = useMemo(() => nodes.reduce<Record<string, number>>((sizes, node) => {
    const degree = nodeDegrees[node.id] ?? 0;
    if (node.kind === "raw") sizes[node.id] = Math.min(26, 16 + degree * 4);
    else if (node.kind === "source") sizes[node.id] = Math.min(44, 22 + degree * 3.2);
    else if (node.kind === "progress") sizes[node.id] = Math.min(38, 22 + degree * 4);
    else sizes[node.id] = Math.min(32, 15 + degree * 3.5);
    return sizes;
  }, {}), [nodeDegrees, nodes]);
  const nodePairs = useMemo(() => nodes.flatMap((nodeA, index) =>
    nodes.slice(index + 1).map((nodeB) => ({ nodeA, nodeB }))
  ), [nodes]);
  const linkedNodePairs = useMemo(() => new Set(links.map((link) => linkKey(link.from, link.to))), [links]);

  function areNodesLinked(nodeAId: string, nodeBId: string) {
    return linkedNodePairs.has(linkKey(nodeAId, nodeBId));
  }

  function isRawSourceLink(link: GraphLink) {
    const from = nodeById.get(link.from);
    const to = nodeById.get(link.to);
    const kinds = [from?.kind, to?.kind];
    return kinds.includes("raw") && kinds.includes("source");
  }

  function nodeSize(node: GraphNode) {
    return nodeSizes[node.id] ?? 20;
  }

  function physicsNodeRadius(node: GraphNode) {
    return (nodeSize(node) / 2) * GRAPH_PHYSICS.collisionRadiusMultiplier;
  }

  function idealLinkDistanceValue(link: GraphLink) {
    const from = nodes.find((node) => node.id === link.from);
    const to = nodes.find((node) => node.id === link.to);
    if (!from || !to) return GRAPH_PHYSICS.linkDistance.fallback * GRAPH_PHYSICS.linkDistanceMultiplier;

    const kinds = [graphNodeKind(from), graphNodeKind(to)];
    let distance = GRAPH_PHYSICS.linkDistance.concept;
    if (kinds.every((kind) => kind === "source")) distance = GRAPH_PHYSICS.linkDistance.source;
    else if (kinds.includes("raw")) distance = GRAPH_PHYSICS.linkDistance.raw;
    else if (kinds.includes("progress")) distance = GRAPH_PHYSICS.linkDistance.progress;
    else if (kinds.includes("source") && kinds.includes("concept")) distance = GRAPH_PHYSICS.linkDistance.sourceConcept;
    return distance * GRAPH_PHYSICS.linkDistanceMultiplier;
  }

  const linkForces = useMemo(() => links.map((link) => ({
    ...link,
    idealDistance: idealLinkDistanceValue(link),
    weight: 1 + Math.min(0.85, ((nodeDegrees[link.from] ?? 0) + (nodeDegrees[link.to] ?? 0)) * 0.035)
  })), [links, nodeDegrees, nodes]); // eslint-disable-line react-hooks/exhaustive-deps

  function pairDistanceValue(nodeA: GraphNode, nodeB: GraphNode) {
    if (areNodesLinked(nodeA.id, nodeB.id)) return 0;

    const base = physicsNodeRadius(nodeA) + physicsNodeRadius(nodeB);
    const kindA = graphNodeKind(nodeA);
    const kindB = graphNodeKind(nodeB);
    let distance = base + 48;
    if (kindA === "source" && kindB === "source") distance = base + 118;
    else if (kindA === "source" || kindB === "source") distance = base + 58;
    else if (kindA === "raw" || kindB === "raw") distance = base + 42;
    const typeMultiplier = kindA === "source" && kindB === "source"
      ? GRAPH_PHYSICS.sourceNodeDistanceMultiplier
      : 1;
    return distance * GRAPH_PHYSICS.nodeDistanceMultiplier * typeMultiplier;
  }

  const pairForces = useMemo(() => nodePairs.map(({ nodeA, nodeB }) => ({
    nodeA,
    nodeB,
    linked: areNodesLinked(nodeA.id, nodeB.id),
    desiredDistance: pairDistanceValue(nodeA, nodeB),
    minDistance: physicsNodeRadius(nodeA) + physicsNodeRadius(nodeB)
  })), [linkedNodePairs, nodePairs, nodeSizes]); // eslint-disable-line react-hooks/exhaustive-deps

  function createRandomNodePositionsForCurrentGraph() {
    const outerRadiusX = GRAPH_WIDTH * 0.18;
    const outerRadiusY = GRAPH_HEIGHT * 0.17;
    const innerRadiusX = GRAPH_WIDTH * 0.08;
    const innerRadiusY = GRAPH_HEIGHT * 0.075;
    const primaryNodeId = nodes.find((node) => node.kind === "source")?.id ?? nodes[0]?.id;

    return Object.fromEntries(
      nodes.map((node) => {
        if (node.id === primaryNodeId) return [node.id, GRAPH_CENTER];

        const isPrimaryNode = node.kind === "source" || node.kind === "progress";
        const angle = randomBetween(0, Math.PI * 2);
        const ringIndex = isPrimaryNode || Math.random() > 0.58 ? 0 : 1;
        const radiusX = ringIndex === 0 ? outerRadiusX : innerRadiusX;
        const radiusY = ringIndex === 0 ? outerRadiusY : innerRadiusY;
        const jitterX = randomBetween(-22, 22);
        const jitterY = randomBetween(-18, 18);

        return [node.id, {
          x: GRAPH_CENTER.x + Math.cos(angle) * radiusX + jitterX,
          y: GRAPH_CENTER.y + Math.sin(angle) * radiusY + jitterY
        }];
      })
    );
  }

  const initialNodePositions = useMemo(
    () => createRandomNodePositionsForCurrentGraph(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [graphSignature]
  );

  function readGraphCacheForCurrentGraph() {
    if (typeof window === "undefined") return null;

    try {
      const rawCache = window.localStorage.getItem(GRAPH_CACHE_KEY);
      if (!rawCache) return null;

      const cache = JSON.parse(rawCache) as Partial<GraphCache>;
      if (cache.signature !== graphSignature || !cache.positions || !cache.pan || typeof cache.zoom !== "number") {
        return null;
      }

      const hasEveryNode = nodes.every((node) => {
        const position = cache.positions?.[node.id];
        return typeof position?.x === "number" && typeof position?.y === "number";
      });

      return hasEveryNode ? cache as GraphCache : null;
    } catch {
      return null;
    }
  }

  function cachedOrInitialPositionsForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.positions ?? initialNodePositions;
  }

  function cachedOrInitialPanForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.pan ?? { x: 0, y: 0 };
  }

  function cachedOrInitialZoomForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.zoom ?? 1;
  }

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null);
  const [visibleNodeCount, setVisibleNodeCount] = useState(0);
  const [graphZoom, setGraphZoom] = useState(cachedOrInitialZoomForCurrentGraph);
  const [graphPan, setGraphPan] = useState<NodePosition>(cachedOrInitialPanForCurrentGraph);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const nodePositionsRef = useRef<NodePositionMap>(cachedOrInitialPositionsForCurrentGraph());
  const selectedNodeIdRef = useRef(selectedNodeId);
  const draggingNodeIdRef = useRef(draggingNodeId);
  const visibleNodeCountRef = useRef(visibleNodeCount);
  const graphZoomRef = useRef(graphZoom);
  const graphPanRef = useRef(graphPan);
  const panDragRef = useRef<{ pointerId: number; button: number; startX: number; startY: number; startPan: NodePosition } | null>(null);
  const focusTransitionRef = useRef<{ from: string | null; to: string | null; startedAt: number }>({ from: null, to: null, startedAt: 0 });
  const isPointerHeldRef = useRef(false);
  const activePointerIdRef = useRef<number | null>(null);
  const cacheWriteRef = useRef<number | null>(null);
  const tickGraphRef = useRef<(positions: NodePositionMap, anchorId: string | null) => NodePositionMap>((positions) => positions);
  const drawGraphRef = useRef<() => void>(() => {});
  const isRevealingGraph = visibleNodeCount < nodes.length;

  useEffect(() => {
    const nextPositions = cachedOrInitialPositionsForCurrentGraph();
    const nextPan = cachedOrInitialPanForCurrentGraph();
    const nextZoom = cachedOrInitialZoomForCurrentGraph();
    nodePositionsRef.current = nextPositions;
    graphPanRef.current = nextPan;
    graphZoomRef.current = nextZoom;
    setGraphPan(nextPan);
    setGraphZoom(nextZoom);
    setVisibleNodeCount(0);
    setSelectedNodeId(null);
    selectedNodeIdRef.current = null;
    drawGraphRef.current();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphSignature, initialNodePositions]);

  useEffect(() => {
    selectedNodeIdRef.current = selectedNodeId;
  }, [selectedNodeId]);

  useEffect(() => {
    draggingNodeIdRef.current = draggingNodeId;
  }, [draggingNodeId]);

  useEffect(() => {
    visibleNodeCountRef.current = visibleNodeCount;
    drawGraphRef.current();
  }, [visibleNodeCount, selectedNodeId]);

  useEffect(() => {
    graphZoomRef.current = graphZoom;
    drawGraphRef.current();
  }, [graphZoom]);

  useEffect(() => {
    graphPanRef.current = graphPan;
    drawGraphRef.current();
  }, [graphPan]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    drawGraphRef.current();
    const observer = new ResizeObserver(() => drawGraphRef.current());
    observer.observe(canvas);
    return () => observer.disconnect();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      event.stopPropagation();
      const nextZoom = clampZoom(graphZoomRef.current * Math.exp(-event.deltaY * GRAPH_ZOOM.wheelSensitivity));
      graphZoomRef.current = nextZoom;
      setGraphZoom(nextZoom);
      scheduleGraphCacheWrite();
    };

    canvas.addEventListener("wheel", handleWheel, { passive: false });
    return () => canvas.removeEventListener("wheel", handleWheel);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let nextIndex = 0;
    setVisibleNodeCount(0);
    const intervalId = window.setInterval(() => {
      nextIndex += 1;
      setVisibleNodeCount(Math.min(nodes.length, nextIndex));
      if (nextIndex >= nodes.length) window.clearInterval(intervalId);
    }, NODE_REVEAL_INTERVAL_MS);

    return () => window.clearInterval(intervalId);
  }, [graphSignature, nodes.length]);

  function scheduleGraphCacheWrite() {
    if (typeof window === "undefined") return;
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);

    cacheWriteRef.current = window.setTimeout(() => {
      const cache: GraphCache = {
        signature: graphSignature,
        positions: nodePositionsRef.current,
        pan: graphPanRef.current,
        zoom: graphZoomRef.current
      };

      window.localStorage.setItem(GRAPH_CACHE_KEY, JSON.stringify(cache));
      cacheWriteRef.current = null;
    }, 700);
  }

  useEffect(() => () => {
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);
  }, []);

  const setFocusedNode = useCallback((nodeId: string | null) => {
    if (selectedNodeIdRef.current === nodeId) return;
    focusTransitionRef.current = {
      from: selectedNodeIdRef.current,
      to: nodeId,
      startedAt: performance.now()
    };
    selectedNodeIdRef.current = nodeId;
    setSelectedNodeId(nodeId);
    drawGraphRef.current();
  }, []);

  const stopDragging = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && activePointerIdRef.current !== pointerId) return;
    isPointerHeldRef.current = false;
    activePointerIdRef.current = null;
    setFocusedNode(null);
    setDraggingNodeId(null);
    draggingNodeIdRef.current = null;
  }, [setFocusedNode]);

  const stopPanning = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && panDragRef.current?.pointerId !== pointerId) return;
    panDragRef.current = null;
  }, []);

  useEffect(() => {
    const stopActiveDrag = (event?: PointerEvent) => {
      stopDragging(event?.pointerId);
      stopPanning(event?.pointerId);
    };
    const stopDragOnBlur = () => {
      stopDragging();
      stopPanning();
    };

    window.addEventListener("pointerup", stopActiveDrag);
    window.addEventListener("pointercancel", stopActiveDrag);
    window.addEventListener("blur", stopDragOnBlur);
    return () => {
      window.removeEventListener("pointerup", stopActiveDrag);
      window.removeEventListener("pointercancel", stopActiveDrag);
      window.removeEventListener("blur", stopDragOnBlur);
    };
  }, [stopDragging, stopPanning]);

  function getGraphDistances(sourceId: string) {
    const distances: Record<string, number> = { [sourceId]: 0 };
    const queue = [sourceId];

    for (let index = 0; index < queue.length; index += 1) {
      const current = queue[index];
      const nextDistance = distances[current] + 1;

      links.forEach((link) => {
        const neighbor = link.from === current ? link.to : link.to === current ? link.from : null;
        if (!neighbor || distances[neighbor] !== undefined) return;
        distances[neighbor] = nextDistance;
        queue.push(neighbor);
      });
    }

    return distances;
  }

  function influenceForDistance(distance: number | undefined) {
    if (distance === 0) return 1;
    if (distance === 1) return 0.34;
    if (distance === 2) return 0.16;
    if (distance === 3) return 0.07;
    if (distance !== undefined) return 0.03;
    return 0;
  }

  function canvasWorldScale(canvas: HTMLCanvasElement) {
    const cssWidth = canvas.clientWidth || canvas.width;
    const worldWidth = Math.max(GRAPH_WORLD_MIN_WIDTH, cssWidth * 2 + 1040);
    return (worldWidth / GRAPH_WIDTH) * graphZoomRef.current;
  }

  function graphToCanvas(position: NodePosition, canvas: HTMLCanvasElement) {
    const scale = canvasWorldScale(canvas);
    const pan = graphPanRef.current;
    return {
      x: canvas.clientWidth / 2 + pan.x + (position.x - GRAPH_CENTER.x) * scale,
      y: canvas.clientHeight / 2 + pan.y + (position.y - GRAPH_CENTER.y) * scale
    };
  }

  function canvasToGraph(clientX: number, clientY: number, canvas: HTMLCanvasElement) {
    const rect = canvas.getBoundingClientRect();
    const scale = canvasWorldScale(canvas);
    const pan = graphPanRef.current;
    return {
      x: GRAPH_CENTER.x + (clientX - rect.left - canvas.clientWidth / 2 - pan.x) / scale,
      y: GRAPH_CENTER.y + (clientY - rect.top - canvas.clientHeight / 2 - pan.y) / scale
    };
  }

  function hitTestNode(clientX: number, clientY: number) {
    const canvas = canvasRef.current;
    if (!canvas) return null;

    const visibleCount = visibleNodeCountRef.current;
    for (let index = visibleCount - 1; index >= 0; index -= 1) {
      const node = nodes[index];
      const position = nodePositionsRef.current[node.id] ?? initialNodePositions[node.id];
      const screenPosition = graphToCanvas(position, canvas);
      const radius = nodeSize(node) / 2;
      const distance = Math.hypot(clientX - canvas.getBoundingClientRect().left - screenPosition.x, clientY - canvas.getBoundingClientRect().top - screenPosition.y);
      if (distance <= Math.max(12, radius + 6)) return node;
    }

    return null;
  }

  function drawNodeLabel(context: CanvasRenderingContext2D, node: GraphNode, x: number, y: number, radius: number) {
    const labelY = y + radius + 18;
    context.font = node.kind === "source" ? "600 14px Inter, sans-serif" : "12px Inter, sans-serif";
    context.textAlign = "center";
    context.textBaseline = "middle";

    if (node.kind === "source") {
      const textWidth = context.measureText(node.label).width;
      const pillWidth = textWidth + 34;
      const pillHeight = 30;
      const pillX = x - pillWidth / 2;
      const pillY = labelY - pillHeight / 2;

      context.fillStyle = "#fff";
      context.shadowColor = "rgba(43, 52, 66, 0.12)";
      context.shadowBlur = 8;
      context.shadowOffsetY = 2;
      context.beginPath();
      context.roundRect(pillX, pillY, pillWidth, pillHeight, 15);
      context.fill();
      context.shadowColor = "transparent";
      context.fillStyle = "#39414d";
      context.fillText(node.label, x, labelY);
      return;
    }

    context.fillStyle = "#627085";
    context.fillText(node.label, x, labelY);
  }

  function drawGraph() {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const pixelRatio = window.devicePixelRatio || 1;
    const cssWidth = canvas.clientWidth;
    const cssHeight = canvas.clientHeight;
    const nextWidth = Math.max(1, Math.floor(cssWidth * pixelRatio));
    const nextHeight = Math.max(1, Math.floor(cssHeight * pixelRatio));
    if (canvas.width !== nextWidth || canvas.height !== nextHeight) {
      canvas.width = nextWidth;
      canvas.height = nextHeight;
    }

    const context = canvas.getContext("2d");
    if (!context) return;

    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    context.clearRect(0, 0, cssWidth, cssHeight);
    const visibleNodeIds = new Set(nodes.slice(0, visibleNodeCountRef.current).map((node) => node.id));
    const positions = nodePositionsRef.current;
    const transition = focusTransitionRef.current;
    const rawProgress = Math.min(1, Math.max(0, (performance.now() - transition.startedAt) / FOCUS_TRANSITION_MS));
    const transitionProgress = rawProgress * rawProgress * (3 - rawProgress * 2);

    function directNodeIds(focusNodeId: string | null) {
      const focusedNodeIds = new Set<string>();
      if (!focusNodeId || !visibleNodeIds.has(focusNodeId)) return focusedNodeIds;
      focusedNodeIds.add(focusNodeId);
      for (const link of links) {
        if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
        if (link.from !== focusNodeId && link.to !== focusNodeId) continue;
        focusedNodeIds.add(link.from === focusNodeId ? link.to : link.from);
      }
      return focusedNodeIds;
    }

    const previousFocusedNodeIds = directNodeIds(transition.from);
    const nextFocusedNodeIds = directNodeIds(transition.to);

    function mix(previous: number, next: number) {
      return previous + (next - previous) * transitionProgress;
    }

    function nodeFocusAmount(nodeId: string) {
      const previous = transition.from ? (previousFocusedNodeIds.has(nodeId) ? 1 : 0) : 1;
      const next = transition.to ? (nextFocusedNodeIds.has(nodeId) ? 1 : 0) : 1;
      return mix(previous, next);
    }

    function nodeSelectedAmount(nodeId: string) {
      return mix(transition.from === nodeId ? 1 : 0, transition.to === nodeId ? 1 : 0);
    }

    function linkFocusAmount(link: GraphLink) {
      const previous = transition.from ? (link.from === transition.from || link.to === transition.from ? 1 : 0) : 1;
      const next = transition.to ? (link.from === transition.to || link.to === transition.to ? 1 : 0) : 1;
      return mix(previous, next);
    }

    function linkHighlightAmount(link: GraphLink) {
      return mix(
        transition.from && (link.from === transition.from || link.to === transition.from) ? 1 : 0,
        transition.to && (link.from === transition.to || link.to === transition.to) ? 1 : 0
      );
    }

    for (const link of links) {
      if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
      const from = positions[link.from] ?? initialNodePositions[link.from];
      const to = positions[link.to] ?? initialNodePositions[link.to];
      const fromScreen = graphToCanvas(from, canvas);
      const toScreen = graphToCanvas(to, canvas);
      const rawSourceLink = isRawSourceLink(link);
      const focusAmount = linkFocusAmount(link);
      const highlightAmount = linkHighlightAmount(link);
      const baseAlpha = rawSourceLink ? 0.68 : 0.56;
      const fadedAlpha = rawSourceLink ? 0.16 : 0.1;

      context.save();
      context.globalAlpha = fadedAlpha + (baseAlpha - fadedAlpha) * focusAmount;
      context.beginPath();
      context.moveTo(fromScreen.x, fromScreen.y);
      context.lineTo(toScreen.x, toScreen.y);
      context.setLineDash(link.dashed ? [4, 4] : []);
      context.lineWidth = rawSourceLink ? 1.25 : 1.15;
      context.strokeStyle = rawSourceLink ? "#aeb7c5" : "#9faaba";
      context.stroke();

      if (highlightAmount > 0.01) {
        context.globalAlpha = 0.98 * highlightAmount;
        context.setLineDash([]);
        context.lineWidth = 2.6;
        context.strokeStyle = "#ffc117";
        context.beginPath();
        context.moveTo(fromScreen.x, fromScreen.y);
        context.lineTo(toScreen.x, toScreen.y);
        context.stroke();
      }

      context.restore();
    }
    context.setLineDash([]);

    for (let index = 0; index < visibleNodeCountRef.current; index += 1) {
      const node = nodes[index];
      const position = positions[node.id] ?? initialNodePositions[node.id];
      const screenPosition = graphToCanvas(position, canvas);
      const radius = nodeSize(node) / 2;
      const selectedAmount = nodeSelectedAmount(node.id);
      const focusAmount = nodeFocusAmount(node.id);

      context.save();
      context.globalAlpha = 0.16 + 0.84 * focusAmount;

      if (selectedAmount > 0.01) {
        context.fillStyle = node.kind === "raw" ? "rgba(152, 164, 181, 0.14)" : "rgba(48, 56, 68, 0.08)";
        context.globalAlpha = selectedAmount;
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius + 26, 0, Math.PI * 2);
        context.fill();
        context.fillStyle = node.kind === "source" || node.kind === "progress" ? "rgba(255, 193, 23, 0.28)" : "rgba(48, 56, 68, 0.18)";
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius + 12, 0, Math.PI * 2);
        context.fill();
        context.globalAlpha = 0.16 + 0.84 * focusAmount;
      }

      context.beginPath();
      context.arc(screenPosition.x, screenPosition.y, radius, 0, Math.PI * 2);
      if (node.kind === "source") {
        context.fillStyle = "#ffc117";
        context.fill();
      } else if (node.kind === "raw") {
        context.strokeStyle = "#98a4b5";
        context.lineWidth = 1.2;
        context.setLineDash([4, 4]);
        context.stroke();
        context.setLineDash([]);
      } else if (node.kind === "progress") {
        context.fillStyle = "#fff";
        context.fill();
        context.strokeStyle = "#ffc117";
        context.lineWidth = 5;
        context.stroke();
        context.strokeStyle = "#99a4b3";
        context.lineWidth = 2;
        context.setLineDash([5, 4]);
        context.stroke();
        context.setLineDash([]);
        context.fillStyle = "#38414d";
        context.font = "900 10px Inter, sans-serif";
        context.textAlign = "center";
        context.textBaseline = "middle";
        context.fillText(`${node.progress}%`, screenPosition.x, screenPosition.y);
      } else {
        context.fillStyle = "#303844";
        context.fill();
      }

      drawNodeLabel(context, node, screenPosition.x, screenPosition.y, radius);
      context.restore();
    }
  }

  drawGraphRef.current = drawGraph;

  function clampZoom(nextZoom: number) {
    return Math.min(GRAPH_ZOOM.max, Math.max(GRAPH_ZOOM.min, nextZoom));
  }

  function clampPosition(position: NodePosition, nodeId?: string) {
    const node = nodes.find((candidate) => candidate.id === nodeId);
    const margin = node ? nodeSize(node) / 2 + 4 : 0;
    return {
      x: Math.min(GRAPH_WIDTH - margin, Math.max(margin, position.x)),
      y: Math.min(GRAPH_HEIGHT - margin, Math.max(margin, position.y))
    };
  }

  function resolveCollisions(positions: NodePositionMap, anchorId: string | null) {
    const next: NodePositionMap = Object.fromEntries(
      nodes.map((node) => [node.id, positions[node.id] ?? initialNodePositions[node.id]])
    );

    for (let iteration = 0; iteration < 4; iteration += 1) {
      for (const pair of pairForces) {
        const { nodeA, nodeB, minDistance } = pair;
        const posA = next[nodeA.id];
        const posB = next[nodeB.id];
        const dx = posB.x - posA.x;
        const dy = posB.y - posA.y;
        const distance = Math.hypot(dx, dy) || 0.01;
        const overlap = minDistance - distance;

        if (overlap <= 0) continue;

        const pushX = (dx / distance) * overlap;
        const pushY = (dy / distance) * overlap;

        if (anchorId && nodeA.id === anchorId) {
          next[nodeB.id] = clampPosition({ x: posB.x + pushX, y: posB.y + pushY }, nodeB.id);
        } else if (anchorId && nodeB.id === anchorId) {
          next[nodeA.id] = clampPosition({ x: posA.x - pushX, y: posA.y - pushY }, nodeA.id);
        } else {
          next[nodeA.id] = clampPosition({ x: posA.x - pushX / 2, y: posA.y - pushY / 2 }, nodeA.id);
          next[nodeB.id] = clampPosition({ x: posB.x + pushX / 2, y: posB.y + pushY / 2 }, nodeB.id);
        }
      }
    }

    return next;
  }

  function tickGraph(positions: NodePositionMap, anchorId: string | null) {
    const next: NodePositionMap = Object.fromEntries(
      nodes.map((node) => [node.id, positions[node.id] ?? initialNodePositions[node.id]])
    );
    const deltas: Record<string, NodePosition> = Object.fromEntries(
      nodes.map((node) => [node.id, { x: 0, y: 0 }])
    );

    linkForces.forEach((link) => {
      const from = next[link.from];
      const to = next[link.to];
      if (!from || !to) return;

      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const distance = Math.hypot(dx, dy) || 0.01;
      const revealBoost = isRevealingGraph ? GRAPH_PHYSICS.revealLinkBoost : 1;
      const force = (distance - link.idealDistance) * GRAPH_PHYSICS.linkStrength * link.weight * revealBoost;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;

      if (link.from !== anchorId) {
        deltas[link.from].x += fx;
        deltas[link.from].y += fy;
      }

      if (link.to !== anchorId) {
        deltas[link.to].x -= fx;
        deltas[link.to].y -= fy;
      }
    });

    for (const pair of pairForces) {
      const { nodeA, nodeB, linked, desiredDistance } = pair;
      const posA = next[nodeA.id];
      const posB = next[nodeB.id];
      const dx = posB.x - posA.x;
      const dy = posB.y - posA.y;
      const distance = Math.hypot(dx, dy) || 0.01;
      if (linked || distance >= desiredDistance || distance >= GRAPH_PHYSICS.repulsionRange) continue;

      const proximity = 1 - distance / GRAPH_PHYSICS.repulsionRange;
      const force = (desiredDistance - distance) * GRAPH_PHYSICS.repulsionStrength * proximity;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;

      if (nodeA.id !== anchorId) {
        deltas[nodeA.id].x -= fx;
        deltas[nodeA.id].y -= fy;
      }

      if (nodeB.id !== anchorId) {
        deltas[nodeB.id].x += fx;
        deltas[nodeB.id].y += fy;
      }
    }

    nodes.forEach((node) => {
      if (node.id === anchorId) return;
      const initialPosition = initialNodePositions[node.id];
      const position = next[node.id] ?? initialPosition;
      const centerStrength = GRAPH_PHYSICS.centerStrength * (isRevealingGraph ? GRAPH_PHYSICS.revealCenterBoost : 1);
      deltas[node.id].x += (GRAPH_CENTER.x - position.x) * centerStrength;
      deltas[node.id].y += (GRAPH_CENTER.y - position.y) * centerStrength;
      deltas[node.id].x += (initialPosition.x - position.x) * GRAPH_PHYSICS.originStrength;
      deltas[node.id].y += (initialPosition.y - position.y) * GRAPH_PHYSICS.originStrength;
    });

    const nextPositions = resolveCollisions(Object.fromEntries(
      nodes.map((node) => {
        const position = next[node.id] ?? initialNodePositions[node.id];
        const delta = deltas[node.id];
        if (node.id === anchorId) return [node.id, clampPosition(position, node.id)];
        const damping = isRevealingGraph ? GRAPH_PHYSICS.revealDamping : GRAPH_PHYSICS.damping;

        return [node.id, clampPosition({
          x: position.x + delta.x * damping,
          y: position.y + delta.y * damping
        }, node.id)];
      })
    ), anchorId);

    if (!isRevealingGraph && !anchorId) {
      const maxMovement = nodes.reduce((movement, node) => {
        const previous = positions[node.id] ?? initialNodePositions[node.id];
        const current = nextPositions[node.id] ?? previous;
        return Math.max(movement, Math.hypot(current.x - previous.x, current.y - previous.y));
      }, 0);

      if (maxMovement < GRAPH_PHYSICS.settleThreshold) return positions;
    }

    return nextPositions;
  }

  tickGraphRef.current = tickGraph;

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
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function moveNode(event: ReactPointerEvent<HTMLDivElement>, node: GraphNode) {
    if (!isPointerHeldRef.current || activePointerIdRef.current !== event.pointerId) return;
    if (event.pointerType === "mouse" && (event.buttons & 1) !== 1) return;
    if (
      event.clientX < 0 ||
      event.clientY < 0 ||
      event.clientX > window.innerWidth ||
      event.clientY > window.innerHeight
    ) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
      stopDragging(event.pointerId);
      return;
    }

    const canvas = canvasRef.current;
    if (!canvas) return;

    const nextPosition = clampPosition(canvasToGraph(event.clientX, event.clientY, canvas), node.id);
    const distances = getGraphDistances(node.id);
    const current = nodePositionsRef.current;
    const draggedPosition = current[node.id] ?? initialNodePositions[node.id];
    const moved = {
      ...current,
      ...Object.fromEntries(
        nodes.map((candidate) => {
          const currentPosition = current[candidate.id] ?? initialNodePositions[candidate.id];
          const influence = influenceForDistance(distances[candidate.id]);
          const delta = {
            x: (nextPosition.x - draggedPosition.x) * influence,
            y: (nextPosition.y - draggedPosition.y) * influence
          };

          return [candidate.id, clampPosition({
            x: currentPosition.x + delta.x,
            y: currentPosition.y + delta.y
          }, candidate.id)];
        })
      )
    };

    nodePositionsRef.current = resolveCollisions(moved, node.id);
    scheduleGraphCacheWrite();
    drawGraphRef.current();
  }

  function startPanning(event: ReactPointerEvent<HTMLDivElement>) {
    const isPrimaryButton = event.button === 0;
    const isMiddleButton = event.button === 1;
    if (!isPrimaryButton && !isMiddleButton) return;

    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    const hitNode = isPrimaryButton ? hitTestNode(event.clientX, event.clientY) : null;
    if (hitNode) {
      isPointerHeldRef.current = true;
      activePointerIdRef.current = event.pointerId;
      setFocusedNode(hitNode.id);
      setDraggingNodeId(hitNode.id);
      draggingNodeIdRef.current = hitNode.id;
      drawGraphRef.current();
      return;
    }

    panDragRef.current = {
      pointerId: event.pointerId,
      button: event.button,
      startX: event.clientX,
      startY: event.clientY,
      startPan: graphPanRef.current
    };
  }

  function updatePanning(event: ReactPointerEvent<HTMLDivElement>) {
    const draggedNodeId = draggingNodeIdRef.current;
    if (draggedNodeId) {
      const node = nodes.find((candidate) => candidate.id === draggedNodeId);
      if (node) moveNode(event, node);
      return;
    }

    const panDrag = panDragRef.current;
    if (!panDrag || panDrag.pointerId !== event.pointerId) return;
    const expectedButtonMask = panDrag.button === 1 ? 4 : 1;
    if (event.pointerType === "mouse" && (event.buttons & expectedButtonMask) !== expectedButtonMask) {
      stopPanning(event.pointerId);
      return;
    }

    const nextPan = {
      x: panDrag.startPan.x + event.clientX - panDrag.startX,
      y: panDrag.startPan.y + event.clientY - panDrag.startY
    };
    graphPanRef.current = nextPan;
    setGraphPan(nextPan);
    scheduleGraphCacheWrite();
  }

  function updateHover(event: ReactPointerEvent<HTMLDivElement>) {
    if (draggingNodeIdRef.current || panDragRef.current) return;
    setFocusedNode(hitTestNode(event.clientX, event.clientY)?.id ?? null);
  }

  function stopCanvasPanning(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    const wasDragging = activePointerIdRef.current === event.pointerId;
    if (wasDragging) {
      stopDragging(event.pointerId);
      setFocusedNode(hitTestNode(event.clientX, event.clientY)?.id ?? null);
    }
    if (panDragRef.current?.pointerId !== event.pointerId) return;
    stopPanning(event.pointerId);
  }

  const rawNodeCount = nodes.filter((node) => node.kind === "raw").length;
  const sourceNodeCount = nodes.filter((node) => node.kind === "source").length;
  const progressNodeCount = nodes.filter((node) => node.kind === "progress").length;
  const conceptNodeCount = nodes.filter((node) => !node.kind || node.kind === "concept").length;

  return (
    <section className="graph-stage" aria-label="자료 관계 그래프">
      <div className="filter-chips">
        <span><SvgIcon src={rawPageIcon} className="chip-icon raw" />원본 raw {rawNodeCount}</span>
        <span><SvgIcon src={sourcePageIcon} className="chip-icon source" />source page {sourceNodeCount}</span>
        <span><SvgIcon src={conceptPageIcon} className="chip-icon concept" />concept page {conceptNodeCount}</span>
        {progressNodeCount > 0 && <span><SvgIcon src={lightningIcon} className="chip-icon source" />processing {progressNodeCount}</span>}
      </div>

      <div
        className="graph-canvas"
        onPointerDown={startPanning}
        onPointerMove={(event) => {
          updatePanning(event);
          updateHover(event);
        }}
        onPointerUp={stopCanvasPanning}
        onPointerCancel={stopCanvasPanning}
        onAuxClick={(event) => event.preventDefault()}
        onPointerLeave={() => {
          if (!draggingNodeIdRef.current && !panDragRef.current) setFocusedNode(null);
        }}
        onLostPointerCapture={(event) => {
          stopDragging(event.pointerId);
          stopPanning(event.pointerId);
        }}
      >
        <canvas ref={canvasRef} className="graph-surface" aria-label="자료 관계 그래프 캔버스" />
        {nodes.length === 0 && (
          <div className="graph-empty">{loading ? "그래프를 불러오는 중입니다." : "표시할 Wiki node가 없습니다."}</div>
        )}
      </div>
    </section>
  );
}

export default function HomePage() {
  const [isAgentPanelOpen, setIsAgentPanelOpen] = useState(true);
  const [activeView, setActiveView] = useState<RailView>("home");
  const [projects, setProjects] = useState<Project[]>(initialProjects);
  const [draggedItem, setDraggedItem] = useState<{ projectId: string; itemId: string } | null>(null);
  const [dropTarget, setDropTarget] = useState<DropTarget | null>(null);
  const [fileDropTarget, setFileDropTarget] = useState<FileDropTarget | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [editing, setEditing] = useState<EditingState | null>(null);
  const [documents, setDocuments] = useState<DocumentItemResponse[]>([]);
  const [wikiGraph, setWikiGraph] = useState<WikiGraphResponse>({ nodes: [], edges: [] });
  const [isGraphLoading, setIsGraphLoading] = useState(true);
  const [apiError, setApiError] = useState<string | null>(null);
  const editingCancelRef = useRef(false);
  const isHomeView = activeView === "home";
  const graphData = useMemo(() => buildGraphFromBackend(documents, wikiGraph), [documents, wikiGraph]);
  const hasProcessingDocuments = documents.some((document) => document.status === "processing" || document.status === "uploaded");

  const refreshBackendData = useCallback(async () => {
    try {
      const nextData = await fetchBackendData();
      setDocuments(nextData.documents);
      setWikiGraph(nextData.graph);
      setProjects((current) => mergeBackendDocumentsIntoProjects(current, nextData.documents));
      setApiError(null);
    } catch (error) {
      setApiError(error instanceof Error ? error.message : "백엔드 데이터를 불러오지 못했습니다.");
    } finally {
      setIsGraphLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshBackendData();
  }, [refreshBackendData]);

  useEffect(() => {
    if (!hasProcessingDocuments) return;
    const intervalId = window.setInterval(() => {
      void refreshBackendData();
    }, 3000);
    return () => window.clearInterval(intervalId);
  }, [hasProcessingDocuments, refreshBackendData]);

  useEffect(() => {
    if (!contextMenu) return;

    function closeContextMenu() {
      setContextMenu(null);
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") closeContextMenu();
    }

    window.addEventListener("click", closeContextMenu);
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("click", closeContextMenu);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [contextMenu]);

  function createProject() {
    setProjects((current) => {
      const nextIndex = current.length + 1;
      return [
        ...current,
        {
          id: `project-${Date.now()}`,
          title: `새 프로젝트 ${nextIndex}`,
          items: []
        }
      ];
    });
  }

  function addFolder(projectId: string) {
    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      const nextIndex = project.items.length + 1;
      return {
        ...project,
        items: [
          ...project.items,
          {
            id: `${project.id}-folder-${Date.now()}`,
            label: `새 폴더 ${nextIndex}`
          }
        ]
      };
    }));
  }

  function dropUploadFiles(projectId: string, folderId: string | null, files: File[]) {
    const uploadFiles = files.filter(isSupportedUploadFile);
    setFileDropTarget(null);
    if (uploadFiles.length === 0) return;

    const uploadItems = uploadFiles.map((file) => ({
      id: createClientId("upload"),
      label: file.name,
      type: "file" as const,
      status: "uploading" as const
    }));

    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      return { ...project, items: appendItemsToFolder(project.items, folderId, uploadItems) };
    }));

    uploadItems.forEach((item, index) => {
      const file = uploadFiles[index];
      void uploadDocumentFile(file)
        .then((response) => {
          setDocuments((current) => {
            const withoutCurrent = current.filter((document) => document.id !== response.id);
            return [...withoutCurrent, response];
          });
          setProjects((current) => current.map((project) => {
            if (project.id !== projectId) return project;
            return { ...project, items: applyUploadedDocument(project.items, item.id, response) };
          }));
          void refreshBackendData();
        })
        .catch((error: Error) => {
          setProjects((current) => current.map((project) => {
            if (project.id !== projectId) return project;
            return { ...project, items: updateTreeItemStatus(project.items, item.id, "failed", error.message) };
          }));
        });
    });
  }

  function moveFolder(projectId: string, itemId: string, target: DropTarget) {
    if (draggedItem?.projectId !== projectId || draggedItem.projectId !== target.projectId) {
      setDropTarget(null);
      return;
    }

    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      return { ...project, items: moveTreeItem(project.items, itemId, target) };
    }));
    setDropTarget(null);
    setDraggedItem(null);
  }

  function openFolderMenu(event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) {
    event.preventDefault();
    setContextMenu({ projectId, itemId, x: event.clientX, y: event.clientY });
  }

  function renameContextFolder() {
    if (!contextMenu) return;
    const project = projects.find((candidate) => candidate.id === contextMenu.projectId);
    const item = project ? findTreeItem(project.items, contextMenu.itemId) : null;
    if (!item) return;
    editingCancelRef.current = false;
    setEditing({ projectId: contextMenu.projectId, itemId: contextMenu.itemId, label: item.label });
    setContextMenu(null);
  }

  function deleteContextFolder() {
    if (!contextMenu) return;
    setProjects((current) => current.map((project) => {
      if (project.id !== contextMenu.projectId) return project;
      return { ...project, items: removeTreeItem(project.items, contextMenu.itemId).items };
    }));
    setContextMenu(null);
  }

  function commitEditing() {
    if (editingCancelRef.current) {
      editingCancelRef.current = false;
      setEditing(null);
      return;
    }
    if (!editing) return;
    const nextLabel = editing.label.trim();
    if (nextLabel) {
      setProjects((current) => current.map((project) => {
        if (project.id !== editing.projectId) return project;
        return { ...project, items: updateTreeItemLabel(project.items, editing.itemId, nextLabel) };
      }));
    }
    setEditing(null);
  }

  function cancelEditing() {
    editingCancelRef.current = true;
    setEditing(null);
  }

  return (
    <main className={`workspace ${isHomeView && !isAgentPanelOpen ? "is-agent-collapsed" : ""}`}>
      <header className="topbar">
        <div className="brand">
          <button className="app-button" aria-label="메뉴"><Menu size={19} /></button>
          <button className="school">부산대학교 <ChevronDown size={15} /></button>
        </div>
        <label className="search-box">
          <Search size={20} />
          <input placeholder="자료명, 관련 내용 검색" />
        </label>
        <div className="profile">
          <div>
            <strong>메타몽</strong>
            <span>온라인</span>
          </div>
          <SvgIcon src={userCircleIcon} className="profile-icon" />
        </div>
      </header>

      <aside className="rail">
        {railItems.map((item) => (
          <button
            key={item.id}
            className={`rail-item ${activeView === item.id ? "is-active" : ""}`}
            aria-label={item.label}
            aria-pressed={activeView === item.id}
            onClick={() => setActiveView(item.id)}
          >
            <span className="rail-icon"><SvgIcon src={item.icon} /></span>
            <span>{item.label}</span>
          </button>
        ))}
      </aside>

      {isHomeView ? (
        <>
          <aside className="sidebar">
            <h1>자료 관리</h1>
            <button className="create-project" onClick={createProject}>프로젝트 만들기 <Plus size={16} /></button>

            {projects.map((project) => (
              <ProjectSection
                key={project.id}
                project={project}
                onAddFolder={addFolder}
                draggedItemId={draggedItem?.itemId ?? null}
                dropTarget={dropTarget}
                fileDropTarget={fileDropTarget}
                editing={editing}
                onMoveItem={moveFolder}
                onDropFiles={dropUploadFiles}
                onDragStart={(projectId, itemId) => {
                  setDraggedItem({ projectId, itemId });
                  setContextMenu(null);
                }}
                onDragOverItem={(target) => {
                  if (draggedItem?.projectId === target.projectId) setDropTarget(target);
                }}
                onFileDragOver={setFileDropTarget}
                onFileDragLeave={() => setFileDropTarget(null)}
                onDragEnd={() => {
                  setDraggedItem(null);
                  setDropTarget(null);
                  setFileDropTarget(null);
                }}
                onContextMenuItem={openFolderMenu}
                onEditingChange={(label) => {
                  setEditing((current) => current ? { ...current, label } : current);
                }}
                onCommitEditing={commitEditing}
                onCancelEditing={cancelEditing}
              />
            ))}
            {contextMenu && (
              <div
                className="folder-context-menu"
                style={{ left: contextMenu.x, top: contextMenu.y }}
                onClick={(event) => event.stopPropagation()}
              >
                <button type="button" onClick={renameContextFolder}>이름 변경</button>
                <button type="button" className="danger" onClick={deleteContextFolder}>삭제</button>
              </div>
            )}
          </aside>

          {apiError && <div className="api-error-banner">{apiError}</div>}
          <Graph nodes={graphData.nodes} links={graphData.links} loading={isGraphLoading} />

          {!isAgentPanelOpen && (
            <button className="agent-restore" aria-label="Agent 패널 보이기" onClick={() => setIsAgentPanelOpen(true)}>
              <SvgIcon src={sideboxIcon} />
            </button>
          )}

          {isAgentPanelOpen && (
            <aside className="agent-panel">
              <div className="agent-header">
                <div className="agent-mark"><SvgIcon src={sparkleIcon} /></div>
                <div>
                  <h2>Fruition Agent</h2>
                  <p>자료 검색 에이전트 · 부산대 워크스페이스</p>
                </div>
                <button className="panel-action" aria-label="Agent 패널 숨기기" onClick={() => setIsAgentPanelOpen(false)}>
                  <SvgIcon src={sideboxIcon} />
                </button>
              </div>

              <div className="agent-body">
                <div className="question-bubble">
                  이번 학기 &apos;장애 아동 통합 교육&apos; 수업 발표를 준비 중이야. 또래 관계가 정서 발달에 미치는 영향에 관한 수업 자료랑 관련 논문 좀 찾아줄 수 있을까?
                </div>

                <div className="agent-message">
                  <div className="mini-mark"><SvgIcon src={sparkleIcon} /></div>
                  <div>
                    <strong>Fruition Agent</strong>
                    <p>요청을 분석하고 자료를 검색하고 있어요</p>
                  </div>
                </div>

                <StatusList title="서치 명령 실행 중" />
                <StatusList title="서치 명령 실행 중" timed />

                <div className="results">
                  <p>찾은 자료 2건</p>
                  {[
                    ["또래 관계 연구.pdf", "p.14-17 · 정서 발달 상관관계 분석"],
                    ["정서 발달 보고서.pdf", "p.3, p.21 · 사회적 상호작용 사례"]
                  ].map(([title, meta]) => (
                    <button className="result-card" key={title}>
                      <span className="file-box"><SvgIcon src={fileIcon} /></span>
                      <span><strong>{title}</strong><small>{meta}</small></span>
                      <b>Source</b>
                    </button>
                  ))}
                </div>

                <div className="typing"><i /><i /><i /> 답변을 작성하고 있어요...</div>
              </div>

              <div className="composer">
                <Plus size={18} />
                <input placeholder="메시지를 입력하세요..." />
                <button aria-label="전송"><SvgIcon src={frameIcon} /></button>
              </div>
            </aside>
          )}
        </>
      ) : (
        <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
      )}
    </main>
  );
}

function StatusList({ title, timed = false }: { title: string; timed?: boolean }) {
  const steps = [
    ["업로드된 문서 12건 스캔 완료", "done", timed ? "14:22:24" : ""],
    ["'또래 관계' 관련 페이지 8건 추출", "done", timed ? "14:24:04" : ""],
    ["정서 발달 연관 논문 분석 중", "active", timed ? "14:24:58" : ""],
    ["발표용 핵심 자료 정리", "pending", timed ? "14:25:07" : ""]
  ];

  return (
    <div className="status-list">
      <button>{title} <ChevronDown size={14} /></button>
      {steps.map(([label, state, time]) => (
        <div className={`status-row ${state}`} key={`${title}-${label}`}>
          <span />
          <p>{label}</p>
          {time && <time>{time}</time>}
        </div>
      ))}
    </div>
  );
}
