import { lazy } from "react";
import type { ComponentType } from "react";

const MAX_RETRIES = 3;
const RETRY_DELAY_MS = 1000;

function retryImport<T extends ComponentType<unknown>>(
  factory: () => Promise<{ default: T }>,
  retries: number,
): Promise<{ default: T }> {
  return factory().catch((error: unknown) => {
    if (retries <= 0) throw error;
    return new Promise<{ default: T }>((resolve) =>
      setTimeout(
        () => resolve(retryImport(factory, retries - 1)),
        RETRY_DELAY_MS,
      ),
    );
  });
}

/**
 * Like `React.lazy` but retries on chunk-load failure.
 *
 * ```ts
 * const DebugPage = lazyWithRetry(
 *   () => import("../../pages/Settings/Debug"),
 * );
 * ```
 */
export function lazyWithRetry<T extends ComponentType<unknown>>(
  factory: () => Promise<{ default: T }>,
) {
  return lazy(() => retryImport(factory, MAX_RETRIES));
}
