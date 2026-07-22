/**
 * Tests for constants/skill.
 *
 * Covers:
 * - SUPPORTED_SKILL_URL_PREFIXES array structure
 * - isSupportedSkillUrl() happy path and edge cases
 * - SKILL_TAG_FILTER_PREFIX value
 */
import { describe, it, expect } from "vitest";
import {
  SUPPORTED_SKILL_URL_PREFIXES,
  isSupportedSkillUrl,
  SKILL_TAG_FILTER_PREFIX,
} from "./skill";

describe("SUPPORTED_SKILL_URL_PREFIXES", () => {
  it("is a non-empty array of URL prefixes", () => {
    expect(Array.isArray(SUPPORTED_SKILL_URL_PREFIXES)).toBe(true);
    expect(SUPPORTED_SKILL_URL_PREFIXES.length).toBeGreaterThan(0);
  });

  it("contains only https URLs", () => {
    for (const prefix of SUPPORTED_SKILL_URL_PREFIXES) {
      expect(prefix).toMatch(/^https:\/\//);
    }
  });
});

describe("isSupportedSkillUrl", () => {
  it.each([
    ["HTTPS ZIP", "https://example.com/my-skill.zip", true],
    ["GitHub archive ZIP", "https://github.com/user/repo/archive/main.zip", true],
    ["HTML detail page", "https://skills.sh/my-skill", false],
    ["repository page", "https://github.com/user/repo", false],
    ["http (not https)", "http://example.com/my-skill.zip", false],
    ["empty string", "", false],
    ["partial prefix match", "https://skills.sh.other.com/skill", false],
  ])("returns %s for %s → %s", (_, url, expected) => {
    expect(isSupportedSkillUrl(url)).toBe(expected);
  });
});

describe("SKILL_TAG_FILTER_PREFIX", () => {
  it('is "tag:"', () => {
    expect(SKILL_TAG_FILTER_PREFIX).toBe("tag:");
  });
});
