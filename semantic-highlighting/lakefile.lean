import Lake
open Lake DSL

package «semantic-highlighting» where
  license := "MIT"

@[default_target]
lean_lib SemanticHighlighting where
  defaultFacets := #[LeanLib.sharedFacet]

/-- Let Lake resolve the library's platform- and toolchain-specific filename. -/
script «plugin-path» do
  let some lib ← findLeanLib? `SemanticHighlighting
    | throw <| IO.userError "SemanticHighlighting library not found"
  let path ← IO.FS.realPath lib.sharedLibFile
  IO.println (Lean.toJson path.toString).compress
  return 0
