package plugin.lean.tooling;

public enum LeanReleaseChannel {
  STABLE("stable", "leanprover/lean4"),
  BETA("beta", "leanprover/lean4"),
  NIGHTLY("nightly", "leanprover/lean4-nightly");

  private final String indexKey;
  private final String origin;

  LeanReleaseChannel(String indexKey, String origin) {
    this.indexKey = indexKey;
    this.origin = origin;
  }

  public String indexKey() {
    return indexKey;
  }

  public String toolchain(String tag) {
    return origin + ":" + tag;
  }

  public String trackingToolchain() {
    return toolchain(indexKey);
  }
}
