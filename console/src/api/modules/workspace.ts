import { request } from "../request";
import { getApiUrl } from "../config";
import { buildAuthHeaders } from "../authHeaders";
import { downloadFileFromUrl } from "../../utils/downloadFileFromUrl";
import type { MdFileInfo, MdFileContent, DailyMemoryFile } from "../types";
import { AGENT_STORAGE_KEY } from "../../stores/agentStore";

function getSelectedAgentId(): string {
  try {
    const agentStorage =
      sessionStorage.getItem(AGENT_STORAGE_KEY) ||
      localStorage.getItem(AGENT_STORAGE_KEY);
    if (agentStorage) {
      const parsed = JSON.parse(agentStorage);
      const selectedAgent = parsed?.state?.selectedAgent;
      if (selectedAgent) return selectedAgent;
    }
  } catch (error) {
    console.warn("Failed to get selected agent from storage:", error);
  }
  return "default";
}

function generateFallbackFilename(): string {
  const agentId = getSelectedAgentId();
  const timestamp = new Date()
    .toISOString()
    .replace(/[-:]/g, "")
    .replace(/\..+/, "")
    .replace("T", "_")
    .slice(0, 15);
  return `melonpaw-company-workspace_${agentId}_${timestamp}.zip`;
}

function encodePath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

export const workspaceApi = {
  listFiles: () =>
    request<MdFileInfo[]>("/workspace/files").then((files) =>
      files.map((file) => ({
        ...file,
        updated_at: new Date(file.modified_time).getTime(),
      })),
    ),

  loadFile: (fileName: string) =>
    request<MdFileContent>(`/workspace/files/${encodeURIComponent(fileName)}`),

  saveFile: (fileName: string, content: string) =>
    request<Record<string, unknown>>(
      `/workspace/files/${encodeURIComponent(fileName)}`,
      {
        method: "PUT",
        body: JSON.stringify({ content }),
      },
    ),

  downloadWorkspace: () =>
    downloadFileFromUrl(
      getApiUrl("/workspace/download"),
      generateFallbackFilename(),
      {
        headers: buildAuthHeaders(),
        errorMessage: "Workspace download failed",
        preferResponseFilename: true,
      },
    ),

  uploadFile: async (
    file: File,
  ): Promise<{ success: boolean; message: string }> => {
    const formData = new FormData();
    formData.append("file", file);

    return request<{ success: boolean; message: string }>("/workspace/upload", {
      method: "POST",
      body: formData,
    });
  },

  listDailyMemory: () =>
    request<MdFileInfo[]>("/workspace/memory").then((files) =>
      files.map((file) => {
        const basename = file.filename.split("/").pop() || file.filename;
        return {
          ...file,
          date: basename.replace(".md", ""),
          updated_at: new Date(file.modified_time).getTime(),
        } as DailyMemoryFile;
      }),
    ),

  loadDailyMemory: (memoryPath: string) =>
    request<MdFileContent>(`/workspace/memory/${encodePath(memoryPath)}`),

  saveDailyMemory: (memoryPath: string, content: string) =>
    request<Record<string, unknown>>(
      `/workspace/memory/${encodePath(memoryPath)}`,
      {
        method: "PUT",
        body: JSON.stringify({ content }),
      },
    ),

  getSystemPromptFiles: () =>
    request<string[]>("/workspace/system-prompt-files"),

  setSystemPromptFiles: (files: string[]) =>
    request<string[]>("/workspace/system-prompt-files", {
      method: "PUT",
      body: JSON.stringify(files),
    }),
};
