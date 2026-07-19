import type { ComponentType } from "react";
import { LightContextCard } from "../pages/Agent/Config/components/LightContextCard";

interface BackendMapping<T> {
  configField: string;
  component: ComponentType<T>;
  label: string;
  tabKey: string;
}

export const CONTEXT_MANAGER_BACKEND_MAPPINGS: Record<
  string,
  BackendMapping<{ maxInputLength: number }>
> = {
  light: {
    configField: "light_context_config",
    component: LightContextCard,
    label: "light",
    tabKey: "lightContext",
  },
};

export const MEMORY_MANAGER_BACKEND_MAPPINGS: Record<
  string,
  BackendMapping<object>
> = {};

/** Memory modes implemented by the Java runtime. */
export const MEMORY_MANAGER_BACKEND_OPTIONS = [
  { value: "file", label: "Built-in file memory" },
  { value: "none", label: "none (disabled)" },
];

export const CONTEXT_MANAGER_BACKEND_OPTIONS = Object.entries(
  CONTEXT_MANAGER_BACKEND_MAPPINGS,
).map(([value, { label }]) => ({ value, label }));
