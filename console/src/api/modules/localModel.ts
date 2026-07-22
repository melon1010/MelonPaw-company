import { request } from "../request";
import type {
  LocalActionResponse,
  LocalDownloadProgress,
  LocalModelInfo,
  LocalServerStatus,
  StartLocalServerRequest,
} from "../types";

export const localModelApi = {
  getLocalServerStatus: () =>
    request<LocalServerStatus>("/local-models/server"),

  listRecommendedLocalModels: () =>
    request<LocalModelInfo[]>("/local-models/models"),

  startLocalModelDownload: (modelName: string) =>
    request<LocalActionResponse>("/local-models/models/download", {
      method: "POST",
      body: JSON.stringify({ model_name: modelName }),
    }),

  getLocalModelDownloadProgress: () =>
    request<LocalDownloadProgress>("/local-models/models/download"),

  cancelLocalModelDownload: () =>
    request<LocalActionResponse>("/local-models/models/download", {
      method: "DELETE",
    }),

  deleteLocalModel: (modelId: string) =>
    request<LocalActionResponse>(
      `/local-models/models/${encodeURIComponent(modelId)}`,
      {
        method: "DELETE",
      },
    ),

  selectLocalModel: (body: StartLocalServerRequest) =>
    request<{ port: number; model_name: string }>("/local-models/server", {
      method: "POST",
      body: JSON.stringify(body),
    }),
};
