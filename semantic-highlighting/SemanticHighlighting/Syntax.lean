import SemanticHighlighting.Compat

namespace SemanticHighlighting
open Lean Lean.Server.FileWorker
open Compat

private abbrev Ranges := Std.HashSet (SourcePos × SourcePos)

private def insertRange (ranges : Ranges) (stx : Syntax) : Ranges :=
  match stx.getPos?, stx.getTailPos? with
  | some start, some stop => ranges.insert (start, stop)
  | _, _ => ranges

/-- Follow the host's projection and choice branches, excluding suppressed syntax.
Documentation can contain embedded Lean syntax; only ranges actually emitted by the host
as property tokens are adjusted below, so documentation properties retain their priority. -/
private partial def projectionRanges (stx : Syntax) (ranges : Ranges := {}) : Ranges :=
  match stx with
  | `($e.$id:ident) => insertRange (projectionRanges e ranges) id
  | `($e |>.$field:ident) => insertRange (projectionRanges e ranges) field
  | stx =>
    if noHighlightKinds.contains stx.getKind then ranges
    else if stx.isOfKind choiceKind then projectionRanges stx[0] ranges
    else stx.getArgs.foldl (fun ranges child => projectionRanges child ranges) ranges

/-- Reuse the host collector, lowering only the two dotted-field fallbacks. -/
def collectSyntaxTokens (text : FileMap) (stx : Syntax) : Array Token := Id.run do
  let ranges := projectionRanges stx
  return (collectHostSyntaxTokens text stx).map fun token =>
    if token.type == .property && token.priority == 5 &&
        ((tokenRange? token).map ranges.contains).getD false then
      { token with priority := 4 }
    else token

end SemanticHighlighting
