import SemanticHighlighting.Compat.IfDecl
import Lean.Server.FileWorker.SemanticHighlighting
import Lean.Compiler.NameMangling

namespace SemanticHighlighting.Compat
open Lean Lean.Lsp Lean.Server.FileWorker

#if_decl String.Pos.Raw then
  abbrev SourcePos := String.Pos.Raw
else
  abbrev SourcePos := String.Pos

#if_decl Lean.Server.FileWorker.LeanSemanticToken.priority then
  abbrev Token := LeanSemanticToken
else
  /-- Older hosts have no token priorities; keep them in the plugin's adapter. -/
  structure Token extends LeanSemanticToken where
    priority : Nat := 5

#if_decl Lean.Server.FileWorker.LeanSemanticToken.range then
  def tokenOfSyntax? (stx : Syntax) (kind : SemanticTokenType) : Option Token :=
    LeanSemanticToken.ofSyntax? stx kind
else
  def tokenOfSyntax? (stx : Syntax) (kind : SemanticTokenType) : Option Token := do
    let _ ← stx.getPos?
    let _ ← stx.getTailPos?
    return { stx, type := kind }

#if_decl Lean.Server.FileWorker.LeanSemanticToken.range then
  def tokenRange? (token : Token) : Option (SourcePos × SourcePos) :=
    some (token.range.start, token.range.stop)
else
  def tokenRange? (token : Token) : Option (SourcePos × SourcePos) := do
    return (← token.stx.getPos?, ← token.stx.getTailPos?)

#if_decl Lean.Elab.CommandContextInfo.cmdEnv? then
  def completedEnvironment (ctx : Elab.ContextInfo) (snapshotEnv : Environment) : Environment :=
    ctx.cmdEnv?.getD snapshotEnv
else
  def completedEnvironment (_ : Elab.ContextInfo) (snapshotEnv : Environment) : Environment :=
    snapshotEnv

#if_decl Lean.Server.FileWorker.docKinds then
  def collectHostSyntaxTokens (text : FileMap) (stx : Syntax) : Array Token :=
    collectSyntaxBasedSemanticTokens text stx
else
  def collectHostSyntaxTokens (_ : FileMap) (stx : Syntax) : Array Token :=
    (collectSyntaxBasedSemanticTokens stx).map fun token => { toLeanSemanticToken := token }

#if_decl Lean.Server.FileWorker.AbsoluteLspSemanticToken.priority then
  abbrev AbsoluteToken := AbsoluteLspSemanticToken
else
  structure AbsoluteToken extends AbsoluteLspSemanticToken where
    priority : Nat := 5

#if_decl Lean.Server.FileWorker.LeanSemanticToken.priority then
  def toAbsolute (text : FileMap) (start : SourcePos) (stop : Option SourcePos)
      (tokens : Array Token) : Array AbsoluteToken :=
    computeAbsoluteLspSemanticTokens text start stop tokens
else
  def toAbsolute (text : FileMap) (start : SourcePos) (stop : Option SourcePos)
      (tokens : Array Token) : Array AbsoluteToken :=
    tokens.flatMap fun token =>
      (computeAbsoluteLspSemanticTokens text start stop #[token.toLeanSemanticToken]).map fun absolute =>
        { toAbsoluteLspSemanticToken := absolute, priority := token.priority }

#if_decl Lean.Server.FileWorker.handleOverlappingSemanticTokens then
  def resolveOverlaps (tokens : Array AbsoluteToken) : Array AbsoluteToken :=
    handleOverlappingSemanticTokens tokens
else
  /-- Resolve legacy tokens one interval at a time. Higher priority wins, followed by
  the later start, earlier end, and later input position. Only active intervals are scanned. -/
  def resolveOverlaps (tokens : Array AbsoluteToken) : Array AbsoluteToken := Id.run do
    let indexed := tokens.toList.zipIdx |>.filter fun (token, _) => token.pos < token.tailPos
    let mut pending := indexed.mergeSort fun a b => a.1.pos <= b.1.pos
    let boundaries := indexed.flatMap (fun (t, _) => [t.pos, t.tailPos])
      |>.mergeSort (· <= ·) |>.toArray
    let mut active : List (AbsoluteToken × Nat) := []
    let mut result : Array AbsoluteToken := #[]
    let mut lastWinner : Option Nat := none
    for i in [:boundaries.size - 1] do
      let start := boundaries[i]!
      let stop := boundaries[i + 1]!
      if start == stop then continue
      while true do
        match pending with
        | candidate :: rest =>
          if candidate.1.pos > start then break
          active := candidate :: active
          pending := rest
        | [] => break
      active := active.filter fun (t, _) => start < t.tailPos
      let winner := active.foldl (init := none) fun best candidate =>
        match best with
        | none => some candidate
        | some current =>
          let (a, ai) := candidate
          let (b, bi) := current
          if a.priority > b.priority || (a.priority == b.priority &&
              (b.pos < a.pos || (a.pos == b.pos &&
                (a.tailPos < b.tailPos || (a.tailPos == b.tailPos && ai > bi))))) then
            some candidate
          else best
      if let some (token, index) := winner then
        let next := { token with pos := start, tailPos := stop }
        match result.back? with
        | some last =>
          if lastWinner == some index && last.tailPos == start then
            result := result.set! (result.size - 1) { last with tailPos := stop }
          else result := result.push next
        | none => result := result.push next
        lastWinner := some index
      else
        lastWinner := none
    return result

#if_decl Lean.Server.FileWorker.AbsoluteLspSemanticToken.priority then
  def encode (tokens : Array AbsoluteToken) : SemanticTokens :=
    computeDeltaLspSemanticTokens tokens
else
  def encode (tokens : Array AbsoluteToken) : SemanticTokens :=
    computeDeltaLspSemanticTokens (tokens.map (·.toAbsoluteLspSemanticToken))

#if_decl Lean.mkModuleInitializationStem then
  def libraryStem : String :=
    Lean.mkModuleInitializationStem `SemanticHighlighting (some "semantic-highlighting")
else
  def libraryStem : String := "SemanticHighlighting"

end SemanticHighlighting.Compat
