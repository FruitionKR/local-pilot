import type { Element, ElementContent, Root, RootContent } from "hast";
import type { MarkdownSegmentRange } from "./markdownSegments";

export type MarkdownSourceBlock = MarkdownSegmentRange & { blockId: string };

function sourceBlockIdAtLine(blocks: MarkdownSourceBlock[], line: number) {
  return blocks.find((block) => block.startLine <= line && line <= block.endLine)?.blockId;
}

function isFootnoteSection(node: RootContent): node is Element {
  return node.type === "element"
    && node.tagName === "section"
    && Boolean(node.properties?.dataFootnotes);
}

function createSourceBlockElement(blockId: string, children: ElementContent[]): Element {
  return {
    type: "element",
    tagName: "source-block",
    properties: { dataBlockId: blockId },
    children
  };
}

/** 문서 전체 parse 문맥을 유지하면서 최상위 렌더 노드를 기존 source block ID로 감싼다. */
export function createRehypeSourceBlocks(blocks: MarkdownSourceBlock[]) {
  return function rehypeSourceBlocks() {
    return (tree: Root) => {
      const footnoteBlockId = blocks.find(
        (block) => block.kind === "markdown" && /(?:^|\n)\[\^[^\]]+\]:/.test(block.content)
      )?.blockId;
      const wrappedChildren: RootContent[] = [];

      tree.children.forEach((child) => {
        if (child.type === "doctype") {
          wrappedChildren.push(child);
          return;
        }

        const blockId = child.position?.start.line
          ? sourceBlockIdAtLine(blocks, child.position.start.line)
          : isFootnoteSection(child)
            ? footnoteBlockId
            : undefined;

        if (!blockId) {
          const lastChild = wrappedChildren.at(-1);
          if (lastChild?.type === "element" && lastChild.tagName === "source-block") {
            lastChild.children.push(child);
          } else {
            wrappedChildren.push(child);
          }
          return;
        }

        const lastChild = wrappedChildren.at(-1);
        if (
          lastChild?.type === "element"
          && lastChild.tagName === "source-block"
          && lastChild.properties?.dataBlockId === blockId
        ) {
          lastChild.children.push(child);
          return;
        }

        wrappedChildren.push(createSourceBlockElement(blockId, [child]));
      });

      tree.children = wrappedChildren;
    };
  };
}
