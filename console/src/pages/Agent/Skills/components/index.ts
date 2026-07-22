export { SkillCard } from "./SkillCard";
export {
  SkillDrawer,
  parseFrontmatter,
  MAX_TAGS,
  MAX_TAG_LENGTH,
  type SkillDrawerFormValues,
} from "./SkillDrawer";
export { getFileIcon, getSkillVisual } from "./SkillCard";
export {
  getSkillDisplaySource,
  getPoolBuiltinStatusLabel,
  getPoolBuiltinStatusTone,
} from "@/utils/skill";
export { useConflictRenameModal } from "./useConflictRenameModal";
export { ImportHubModal } from "./ImportHubModal";
export { PoolTransferModal } from "./PoolTransferModal";
export { SkillFilterDropdown, TAG_PREFIX } from "./SkillFilterDropdown";
export { HeaderActions } from "./HeaderActions";
export { AddSkillDropdown } from "./AddSkillDropdown";
export { SkillsToolbar } from "./SkillsToolbar";
export { SkillListItem } from "./SkillListItem";

export {
  SUPPORTED_SKILL_URL_PREFIXES,
  isSupportedSkillUrl,
} from "@/constants/skill";

export interface SkillMarket {
  key: string;
  name: string;
  homepage: string;
  urlPrefix: string;
  examples: { label: string; url: string }[];
}

export const skillMarkets: SkillMarket[] = [
  {
    key: "zip",
    name: "HTTPS ZIP",
    homepage: "https://github.com",
    urlPrefix: "https://",
    examples: [],
  },
];
