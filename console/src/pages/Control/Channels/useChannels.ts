import { useState, useEffect, useCallback, useMemo } from "react";
import api from "../../../api";
import type { ChannelHealthMap } from "../../../api";
import { useAgentStore } from "../../../stores/agentStore";

export const MANAGED_CHANNELS = [
  "dingtalk",
  "feishu",
  "wecom",
  "wechat",
  "qq",
] as const;

export function useChannels() {
  const { selectedAgent } = useAgentStore();
  const [channels, setChannels] = useState<
    Record<string, Record<string, unknown>>
  >({});
  const [channelTypes, setChannelTypes] = useState<string[]>([]);
  const [health, setHealth] = useState<ChannelHealthMap>({});
  const [loading, setLoading] = useState(true);

  const fetchChannels = useCallback(async () => {
    setLoading(true);
    try {
      const [data, types, runtimeHealth] = await Promise.all([
        api.listChannels(),
        api.listChannelTypes(),
        api.getChannelsHealth().catch(() => ({})),
      ]);
      if (data)
        setChannels(data as unknown as Record<string, Record<string, unknown>>);
      if (types) setChannelTypes(types);
      if (runtimeHealth) setHealth(runtimeHealth);
    } catch (error) {
      console.error("❌ Failed to load channels:", error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels, selectedAgent]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      api
        .getChannelsHealth()
        .then((runtimeHealth) => setHealth(runtimeHealth || {}))
        .catch(() => undefined);
    }, 10_000);
    return () => window.clearInterval(timer);
  }, [selectedAgent]);

  const orderedKeys = useMemo(
    () => MANAGED_CHANNELS.filter((key) => channelTypes.includes(key)),
    [channelTypes],
  );

  // Read isBuiltin from API response
  const isBuiltin = useCallback(
    (key: string) => Boolean(channels[key]?.isBuiltin),
    [channels],
  );

  return {
    channels,
    health,
    channelTypes,
    orderedKeys,
    isBuiltin,
    loading,
    fetchChannels,
  };
}
