/**
 * Registers all built-in tool cards into the local registry so they are
 * available to both ChatV2 (via CardRegistry)
 * and ChatV1 (via customToolRenderConfig / usePlugins).
 *
 * Call once at application startup (e.g. in main.tsx or App.tsx).
 */

import { toolRenderRegistry } from "../../../plugins/toolRenderRegistry";
import { BUILTIN_CARD_REGISTRY } from "./cards";
import { adaptRegistryForV1 } from "./adapters/v1Adapter";

let registered = false;

/**
 * Register all built-in tool cards so that:
 *
 * 1. ChatV1 picks them up via `usePlugins().toolRenderConfig`
 *    (through `customToolRenderConfig` in @agentscope-ai/chat)
 *
 * 2. ChatV2 can also discover them through the same card registry
 *    (though ChatV2 primarily uses direct registry lookup)
 */
export function registerBuiltinCards(): void {
  if (registered) return;
  registered = true;

  const v1AdaptedCards = adaptRegistryForV1(BUILTIN_CARD_REGISTRY);
  console.info(
    `[builtin-tool-cards] Registering ${
      Object.keys(v1AdaptedCards).length
    } tool cards:`,
    Object.keys(v1AdaptedCards).join(", "),
  );
  toolRenderRegistry.register(v1AdaptedCards);
}
