import { Fragment, type ReactNode } from "react";
import { cx } from "../_lib/classNames";
import type { SourceBlockHighlight } from "../_lib/types";

// citation 강조에 사용하는 색상 팔레트 개수
const CITATION_COLOR_COUNT = 5;

function rankColorClass(rank: number) {
  return `citation-rank-${((rank - 1) % CITATION_COLOR_COUNT) + 1}`;
}

function renderInline(text: string, onCitationClick?: (rank: number) => void, canClickCitation?: (rank: number) => boolean): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\[\[[^\]|]+(?:\|[^\]]+)?\]\]|\[((?:\d+)(?:\s*,\s*\d+)*)\])/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) nodes.push(text.slice(lastIndex, match.index));
    const token = match[0];
    const citationRanks = match[2]?.split(",").map((value) => Number(value.trim())).filter(Number.isFinite) ?? [];
    if (token.startsWith("**")) {
      nodes.push(<strong key={`${match.index}-strong`}>{token.slice(2, -2)}</strong>);
    } else if (token.startsWith("`")) {
      nodes.push(<code key={`${match.index}-code`}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith("[[")) {
      const body = token.slice(2, -2);
      const label = body.includes("|") ? body.split("|")[1] : body;
      nodes.push(<span className="markdown-wikilink" key={`${match.index}-wikilink`}>{label}</span>);
    } else if (citationRanks.length > 0 && onCitationClick && citationRanks.some((rank) => !canClickCitation || canClickCitation(rank))) {
      nodes.push(
        <Fragment key={`${match.index}-citations`}>
          {citationRanks.map((citationRank) => (
            (!canClickCitation || canClickCitation(citationRank)) ? (
              <button
                type="button"
                className={`markdown-citation ${rankColorClass(citationRank)}`}
                key={citationRank}
                onClick={(event) => {
                  event.stopPropagation();
                  onCitationClick(citationRank);
                }}
              >
                [{citationRank}]
              </button>
            ) : `[${citationRank}]`
          ))}
        </Fragment>
      );
    } else {
      nodes.push(token);
    }
    lastIndex = match.index + token.length;
  }

  if (lastIndex < text.length) nodes.push(text.slice(lastIndex));
  return nodes;
}

/** markdown 문자열을 블록 단위 ReactNode 목록으로 파싱한다. MarkdownViewer 본문에서 추출했습니다. */
function parseMarkdownBlocks(
  markdown: string,
  onCitationClick?: (rank: number) => void,
  canClickCitation?: (rank: number) => boolean,
  highlightedBlocks?: SourceBlockHighlight[],
  onBlockRef?: (blockId: string, node: HTMLDivElement | null) => void
): ReactNode[] {
  const blocks: ReactNode[] = [];
  const highlightedBlockRankById = new Map((highlightedBlocks ?? []).map((block) => [block.block_id, block.rank]));
  const lines = markdown.split("\n");
  let index = 0;
  let blockNumber = 0;

  function appendBlock(node: ReactNode) {
    blockNumber += 1;
    const blockId = `B${String(blockNumber).padStart(4, "0")}`;
    const highlightedRank = highlightedBlockRankById.get(blockId);
    blocks.push(
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
        {node}
      </div>
    );
  }

  while (index < lines.length) {
    const line = lines[index];
    const trimmed = line.trim();

    if (!trimmed) {
      index += 1;
      continue;
    }

    if (trimmed === "---" && blocks.length === 0) {
      const frontmatter = [];
      index += 1;
      while (index < lines.length && lines[index].trim() !== "---") {
        frontmatter.push(lines[index]);
        index += 1;
      }
      index += 1;
      appendBlock(
        <details className="markdown-frontmatter" key={`frontmatter-${index}`}>
          <summary>Metadata</summary>
          <pre>{frontmatter.join("\n")}</pre>
        </details>
      );
      continue;
    }

    if (trimmed.startsWith("```")) {
      const code = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      while (code.length > 0 && code[code.length - 1].trim() === "") code.pop();
      index += 1;
      appendBlock(<pre className="markdown-codeblock" key={`code-${index}`}><code>{code.join("\n")}</code></pre>);
      continue;
    }

    if (trimmed.startsWith("# ")) {
      appendBlock(<h1 key={`h1-${index}`}>{renderInline(trimmed.slice(2), onCitationClick, canClickCitation)}</h1>);
      index += 1;
      continue;
    }

    if (trimmed.startsWith("## ")) {
      appendBlock(<h2 key={`h2-${index}`}>{renderInline(trimmed.slice(3), onCitationClick, canClickCitation)}</h2>);
      index += 1;
      continue;
    }

    if (trimmed.startsWith("### ")) {
      appendBlock(<h3 key={`h3-${index}`}>{renderInline(trimmed.slice(4), onCitationClick, canClickCitation)}</h3>);
      index += 1;
      continue;
    }

    if (trimmed === "---" || trimmed === "***") {
      appendBlock(<hr key={`hr-${index}`} />);
      index += 1;
      continue;
    }

    if (/^- /.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^- /.test(lines[index].trim())) {
        items.push(lines[index].trim().slice(2));
        index += 1;
      }
      appendBlock(
        <ul key={`ul-${index}`}>
          {items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item, onCitationClick, canClickCitation)}</li>)}
        </ul>
      );
      continue;
    }

    if (/^\d+\. /.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^\d+\. /.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^\d+\. /, ""));
        index += 1;
      }
      appendBlock(
        <ol key={`ol-${index}`}>
          {items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item, onCitationClick, canClickCitation)}</li>)}
        </ol>
      );
      continue;
    }

    const paragraph = [trimmed];
    index += 1;
    while (index < lines.length && lines[index].trim() && !/^(#{1,3} |[-*]{3}$|- |\d+\. |```)/.test(lines[index].trim())) {
      paragraph.push(lines[index].trim());
      index += 1;
    }
    appendBlock(<p key={`p-${index}`}>{renderInline(paragraph.join(" "), onCitationClick, canClickCitation)}</p>);
  }

  return blocks;
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
  const blocks = parseMarkdownBlocks(markdown, onCitationClick, canClickCitation, highlightedBlocks, onBlockRef);

  return <div className="markdown-viewer">{blocks.map((block, blockIndex) => <Fragment key={blockIndex}>{block}</Fragment>)}</div>;
}
