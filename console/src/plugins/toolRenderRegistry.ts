import type React from "react";

type ToolRenderer = React.FC<any>;

class ToolRenderRegistry {
  private renderers: Record<string, ToolRenderer> = {};
  private listeners = new Set<() => void>();

  register(renderers: Record<string, ToolRenderer>): void {
    Object.assign(this.renderers, renderers);
    this.listeners.forEach((listener) => listener());
  }

  snapshot(): Record<string, ToolRenderer> {
    return { ...this.renderers };
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export const toolRenderRegistry = new ToolRenderRegistry();
