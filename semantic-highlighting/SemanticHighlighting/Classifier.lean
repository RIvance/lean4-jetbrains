import SemanticHighlighting.Compat

namespace SemanticHighlighting
open Lean Lean.Lsp Lean.Server.FileWorker
open Compat

private def typeForSortResult? (type : Expr) : Option SemanticTokenType :=
  (Meta.typeFormerTypeLevelQuick type).map fun u =>
    if u.isAlwaysZero then .enum else .type

private partial def typeExprResult?
  (env : Environment) (lctx : LocalContext) (type : Expr)
  (bvarTypes : List Expr := [])
: Option SemanticTokenType :=
  match type.getAppFn with
  | .sort _ => typeForSortResult? type
  | .forallE _ domain body _ => typeExprResult? env lctx body (domain :: bvarTypes)
  | .const declName us =>
    match env.find? declName with
    | some info => typeForSortResult? (info.instantiateTypeLevelParams us)
    | none => none
  | .fvar fvarId =>
    match lctx.find? fvarId with
    | some localDecl => typeForSortResult? localDecl.type
    | none => none
  | .bvar idx => bvarTypes[idx]?.bind typeForSortResult?
  | _ => none

private def classifyLocal
  (env : Environment) (ti : Elab.TermInfo) (localDecl : LocalDecl)
: Option SemanticTokenType := Id.run do
  let localType := localDecl.type
  -- Recall that `isAuxDecl` is used for declarations currently being elaborated, e.g. recursive
  -- functions and inductive types while their constructors are being processed.
  if localDecl.isAuxDecl then
    if let some tokenType := typeForSortResult? localType then
      return some tokenType
    else
      return some .function
  if let some tokenType := typeForSortResult? localType then
    if localType.isSort && tokenType == .type then
      return some .typeParameter
    else
      return some tokenType
  if localDecl.isImplementationDetail then
    return none
  if typeExprResult? env ti.lctx localType == some .enum then
    return some .variable
  if ti.isBinder then
    return some .parameter
  return some .variable

private def classifyConstant
  (env : Environment) (info : ConstantInfo) (us : List Level := [])
: SemanticTokenType :=
  if env.isProjectionFn info.name then
    .property
  else match info with
  | .ctorInfo _ => .enumMember
  | .inductInfo _ =>
    if isClass env info.name then
      .interface
    else if isStructure env info.name then
      .struct
    else if let some tokenType := typeForSortResult? (info.instantiateTypeLevelParams us) then
      tokenType
    else
      .type
  | .thmInfo _ | .recInfo _ => .function
  | _ =>
    if let some tokenType := typeForSortResult? (info.instantiateTypeLevelParams us) then
      tokenType
    else
      .function

private def findConstInfoWithEnv?
  (ctx : Elab.ContextInfo) (finalEnv : Environment) (declName : Name)
: Option (Environment × ConstantInfo) :=
  -- A declaration can exist before its structure/class metadata has been registered.
  -- Prefer the completed command environment even when the earlier context has its type.
  match finalEnv.find? declName with
  | some info => some (finalEnv, info)
  | none => (ctx.env.find? declName).map (ctx.env, ·)

private def getSemanticTokenSyntax (stx : Syntax) : Syntax :=
  if stx.isOfKind Parser.Term.identProjKind then
    stx[0]
  else if stx.isOfKind ``Parser.Term.dotIdent then
    stx[1]
  else if stx.isOfKind ``Parser.Term.explicitUniv && stx[0].isOfKind ``Parser.Term.dotIdent then
    stx[0][1]
  else match stx with
  | `(Parser.Command.declId|$id$[.{$_ls,*}]?) => id.raw
  | _ => stx

private def getAppFnSyntax? (stx : Syntax) : Option Syntax :=
  if stx.isOfKind ``Parser.Term.app then some stx[0] else none

private def collectTermInfoBasedSemanticToken
  (finalEnv : Environment)
  (ctx : Elab.ContextInfo) (ti : Elab.TermInfo)
: Option Token := Id.run do
  let stx := getSemanticTokenSyntax ti.stx
  if ti.expr.isSort then
    unless stx.isOfKind ``Parser.Term.type || stx.isOfKind ``Parser.Term.sort || stx.isOfKind ``Parser.Term.prop do
      return none
    let stx := stx[0]
    let .original .. := stx.getHeadInfo
      | return none
    return tokenOfSyntax? stx .class
  match ti.expr.getAppFn with
  | Expr.fvar fvarId .. =>
    let some localDecl := ti.lctx.find? fvarId
      | return none
    let some stx :=
      if let `($_:ident) := stx then
        some stx
      else if localDecl.isAuxDecl then
        getAppFnSyntax? stx
      else
        none
      | return none
    let .original .. := stx.getHeadInfo
      | return none
    let `($_:ident) := stx
      | return none
    if localDecl.isAuxDecl then
      if let some declName := ti.lctx.auxDeclToFullName.get? fvarId then
        if let some info := finalEnv.find? declName then
          return tokenOfSyntax? stx (classifyConstant finalEnv info)
    return (classifyLocal ctx.env ti localDecl).bind (tokenOfSyntax? stx ·)
  | Expr.const declName us =>
    let `($_:ident) := stx
      | return none
    let .original .. := stx.getHeadInfo
      | return none
    match findConstInfoWithEnv? ctx finalEnv declName with
    | some (env, info) => return tokenOfSyntax? stx (classifyConstant env info us)
    | none => return none
  | _ => return none

/-- Collects all semantic tokens from the given `Elab.InfoTree`. -/
def collectInfoTokens (snapshotEnv : Environment) (i : Elab.InfoTree) : Array Token :=
  List.toArray <| i.deepestNodes fun ctx info _ => do
    let .ofTermInfo ti := info
      | none
    collectTermInfoBasedSemanticToken (completedEnvironment ctx snapshotEnv) ctx ti

end SemanticHighlighting
