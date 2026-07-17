import type { EnterpriseTaskStatus } from "../../api";

export function isActiveTask(status: EnterpriseTaskStatus): boolean {
  return status === "RUNNING" || status === "WAITING_APPROVAL";
}

export function taskStatusColor(status: EnterpriseTaskStatus): string {
  if (status === "SUCCEEDED") return "success";
  if (status === "RUNNING") return "processing";
  if (status === "WAITING_APPROVAL") return "warning";
  if (["FAILED", "TIMED_OUT", "REJECTED"].includes(status)) return "error";
  return "default";
}
