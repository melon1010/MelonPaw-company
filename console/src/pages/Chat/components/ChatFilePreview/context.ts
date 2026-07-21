import { createContext, useContext } from "react";

export interface ChatPreviewFile {
  name?: string;
  size?: number;
  type?: string;
  url: string;
}

export type OpenChatFilePreview = (file: ChatPreviewFile) => void;

export const ChatFilePreviewContext = createContext<OpenChatFilePreview | null>(
  null,
);

export function useChatFilePreview(): OpenChatFilePreview | null {
  return useContext(ChatFilePreviewContext);
}
