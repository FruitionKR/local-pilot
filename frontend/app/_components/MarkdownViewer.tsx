import { useMemo } from "react";
import type { ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import type { PhrasingContent, Root } from "mdast";
import { visit } from "unist-util-visit";
import { cx } from "../_lib/classNames";
import type { SourceBlockHighlight } from "../_lib/types";

// citation 강조에 사용하는 색상 팔레트 개수
const CITATION_COLOR_COUNT = 5;

function rankColorClass(rank: number) {
  return `citation-rank-${((rank - 1) % CITATION_COLOR_COUNT) + 1}`;
}

/** wikilink([[...]])와 citation([1,2])을 커스텀 노드로 분리하는 remark 플러그인 */
function remarkCustomTokens() {
  return (tree: Root) => {
    visit(tree, "text", (node, index, parent) => {
      if (!parent || index === undefined) return;

      const pattern = /(\[\[[^\]|]+(?:\|[^\]]+)?\]\]|\[(?:\d+)(?:\s*,\s*\d+)*\])/g;
      const value = node.value;
      const replacements: PhrasingContent[] = [];
      let lastIndex = 0;
      let match: RegExpExecArray | null;

      while ((match = pattern.exec(value)) !== null) {
        if (match.index > lastIndex) {
          replacements.push({ type: "text", value: value.slice(lastIndex, match.index) });
        }

        const token = match[0];
        if (token.startsWith("[[")) {
          const body = token.slice(2, -2);
          const label = body.includes("|") ? body.split("|")[1] : body;
          // 커스텀 노드 타입이라 mdast 유니온에 없어 캐스팅한다. hName 기반으로 hast에서 span으로 변환된다.
          replacements.push({
            type: "wikiLinkToken",
            data: { hName: "span", hProperties: { className: "markdown-wikilink" } },
            children: [{ type: "text", value: label }]
          } as unknown as PhrasingContent);
        } else {
          const ranks = token
            .slice(1, -1)
            .split(",")
            .map((part) => Number(part.trim()))
            .filter(Number.isFinite);
          ranks.forEach((rank) => {
            replacements.push({
              type: "citationToken",
              data: { hName: "citation-ref", hProperties: { rank } },
              children: [{ type: "text", value: `[${rank}]` }]
            } as unknown as PhrasingContent);
          });
        }

        lastIndex = match.index + token.length;
      }

      if (replacements.length === 0) return;
      if (lastIndex < value.length) {
        replacements.push({ type: "text", value: value.slice(lastIndex) });
      }

      parent.children.splice(index, 1, ...replacements);
      return index + replacements.length;
    });
  };
}

// 렌더마다 배열 참조가 바뀌면 react-markdown이 재파싱하므로 모듈 상수로 유지한다.
const REMARK_PLUGINS = [remarkGfm, remarkMath, remarkCustomTokens];
const REHYPE_PLUGINS = [rehypeKatex];

type MarkdownSegment = { kind: "frontmatter" | "markdown"; content: string };

/**
 * markdown을 블록 단위 문자열로 분할한다.
 * 분할 순서가 백엔드 block ID(B0001, B0002, ...) 계약과 일치해야 하므로
 * 기존 파서와 동일한 경계 규칙을 유지한다.
 */
function splitMarkdownBlocks(markdown: string): MarkdownSegment[] {
  const segments: MarkdownSegment[] = [];
  const lines = markdown.split("\n");
  let index = 0;

  while (index < lines.length) {
    const trimmed = lines[index].trim();

    if (!trimmed) {
      index += 1;
      continue;
    }

    if (trimmed === "---" && segments.length === 0) {
      const frontmatter = [];
      index += 1;
      while (index < lines.length && lines[index].trim() !== "---") {
        frontmatter.push(lines[index]);
        index += 1;
      }
      index += 1;
      segments.push({ kind: "frontmatter", content: frontmatter.join("\n") });
      continue;
    }

    if (trimmed.startsWith("```")) {
      const code = [lines[index]];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      code.push("```");
      index += 1;
      segments.push({ kind: "markdown", content: code.join("\n") });
      continue;
    }

    if (/^#{1,3} /.test(trimmed) || trimmed === "---" || trimmed === "***") {
      segments.push({ kind: "markdown", content: trimmed });
      index += 1;
      continue;
    }

    if (/^- /.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^- /.test(lines[index].trim())) {
        items.push(lines[index].trim());
        index += 1;
      }
      segments.push({ kind: "markdown", content: items.join("\n") });
      continue;
    }

    if (/^\d+\. /.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^\d+\. /.test(lines[index].trim())) {
        items.push(lines[index].trim());
        index += 1;
      }
      segments.push({ kind: "markdown", content: items.join("\n") });
      continue;
    }

    const paragraph = [trimmed];
    index += 1;
    while (index < lines.length && lines[index].trim() && !/^(#{1,3} |[-*]{3}$|- |\d+\. |```)/.test(lines[index].trim())) {
      paragraph.push(lines[index].trim());
      index += 1;
    }
    segments.push({ kind: "markdown", content: paragraph.join("\n") });
  }

  return segments;
}

export function MarkdownViewer({
  markdown,
  onCitationClick,
  canClickCitation,
  highlightedBlocks,
  onBlockRef
}: {
  markdown: string;
  onCitationClick?: (rank: number) => void;
  canClickCitation?: (rank: number) => boolean;
  highlightedBlocks?: SourceBlockHighlight[];
  onBlockRef?: (blockId: string, node: HTMLDivElement | null) => void;
}) {
  const highlightedBlockRankById = useMemo(
    () => new Map((highlightedBlocks ?? []).map((block) => [block.block_id, block.rank])),
    [highlightedBlocks]
  );
  const segments = useMemo(() => splitMarkdownBlocks(markdown), [markdown]);

  const components = useMemo(() => {
    function CitationRef({ rank, children }: { rank?: number; children?: ReactNode }) {
      const citationRank = Number(rank);
      if (!Number.isFinite(citationRank) || !onCitationClick || (canClickCitation && !canClickCitation(citationRank))) {
        return <>{children}</>;
      }
      return (
        <button
          type="button"
          className={`markdown-citation ${rankColorClass(citationRank)}`}
          onClick={(event) => {
            event.stopPropagation();
            onCitationClick(citationRank);
          }}
        >
          {children}
        </button>
      );
    }

    return {
      pre: ({ children }: { children?: ReactNode }) => <pre className="markdown-codeblock">{children}</pre>,
      "citation-ref": CitationRef
    } as Components;
  }, [canClickCitation, onCitationClick]);

  return (
    <div className="markdown-viewer">
      {segments.map((segment, segmentIndex) => {
        const blockId = `B${String(segmentIndex + 1).padStart(4, "0")}`;
        const highlightedRank = highlightedBlockRankById.get(blockId);

        return (
          <div
            className={cx(
              "markdown-source-block",
              highlightedRank && "is-highlighted",
              highlightedRank && rankColorClass(highlightedRank)
            )}
            data-block-id={blockId}
            data-citation-rank={highlightedRank}
            ref={(element) => onBlockRef?.(blockId, element)}
            key={blockId}
          >
            {segment.kind === "frontmatter" ? (
              <details className="markdown-frontmatter">
                <summary>Metadata</summary>
                <pre>{segment.content}</pre>
              </details>
            ) : (
              <ReactMarkdown remarkPlugins={REMARK_PLUGINS} rehypePlugins={REHYPE_PLUGINS} components={components}>
                {segment.content}
              </ReactMarkdown>
            )}
          </div>
        );
      })}
    </div>
  );
}
