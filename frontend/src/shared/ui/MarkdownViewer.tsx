import { useMemo } from "react";
import type { ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import type { Element } from "hast";
import type { PhrasingContent, Root } from "mdast";
import { visit } from "unist-util-visit";
import { cx } from "@/shared/lib/classNames";
import { splitMarkdownBlockRanges } from "@/shared/lib/markdownSegments";
import { createRehypeSourceBlocks } from "@/shared/lib/markdownSourceBlocks";
import type { SourceBlockHighlight } from "../../../app/_lib/types";

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
  const sourceBlocks = useMemo(
    () => splitMarkdownBlockRanges(markdown).map((segment, index) => ({
      ...segment,
      blockId: `B${String(index + 1).padStart(4, "0")}`
    })),
    [markdown]
  );
  const bodyMarkdown = useMemo(() => {
    const lines = markdown.split("\n");
    sourceBlocks
      .filter((block) => block.kind === "frontmatter")
      .forEach((block) => {
        for (let line = block.startLine; line <= block.endLine; line += 1) {
          lines[line - 1] = "";
        }
      });
    return lines.join("\n");
  }, [markdown, sourceBlocks]);
  const rehypePlugins = useMemo(
    () => [rehypeKatex, createRehypeSourceBlocks(sourceBlocks)],
    [sourceBlocks]
  );

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

    function SourceBlock({ node, children }: { node?: Element; children?: ReactNode }) {
      const blockId = String(node?.properties?.dataBlockId ?? "");
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
        >
          {children}
        </div>
      );
    }

    return {
      pre: ({ children }: { children?: ReactNode }) => <pre className="markdown-codeblock">{children}</pre>,
      "citation-ref": CitationRef,
      "source-block": SourceBlock
    } as Components;
  }, [canClickCitation, highlightedBlockRankById, onBlockRef, onCitationClick]);

  return (
    <div className="markdown-viewer">
      {sourceBlocks
        .filter((block) => block.kind === "frontmatter")
        .map((block) => {
          const highlightedRank = highlightedBlockRankById.get(block.blockId);
          return (
          <div
            className={cx(
              "markdown-source-block",
              highlightedRank && "is-highlighted",
              highlightedRank && rankColorClass(highlightedRank)
            )}
            data-block-id={block.blockId}
            data-citation-rank={highlightedRank}
            ref={(element) => onBlockRef?.(block.blockId, element)}
            key={block.blockId}
          >
            <details className="markdown-frontmatter">
              <summary>Metadata</summary>
              <pre>{block.content}</pre>
            </details>
          </div>
          );
        })}
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} rehypePlugins={rehypePlugins} components={components}>
        {bodyMarkdown}
      </ReactMarkdown>
    </div>
  );
}
