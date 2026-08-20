import type { QueryMessageSummary } from "@/entities/wiki/model/wiki";
import type { RelatedPageBase } from "@/shared/types/shared";

export type ChatSessionResponse = {
  id: string;
  title: string | null;
  created_at: string;
  last_message_at: string;
};

export type ChatSessionListResponse = {
  sessions: ChatSessionResponse[];
};

export type ChatMessageReferenceResponse = {
  id: number;
  reference_type: string;
  rank?: number;
  source_document_id?: string;
  source_block_ids?: string[];
  text?: string;
};

export type ChatMessageRelatedPageResponse = RelatedPageBase & {
  wiki_page_id: string;
  rank: number;
};

export type ChatMessageResponse = QueryMessageSummary & {
  action?: string;
  related_pages?: ChatMessageRelatedPageResponse[];
  references: ChatMessageReferenceResponse[];
};

export type ChatMessagesResponse = {
  messages: ChatMessageResponse[];
};
