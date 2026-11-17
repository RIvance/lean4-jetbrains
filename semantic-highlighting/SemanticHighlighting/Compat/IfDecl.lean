import Lean

namespace SemanticHighlighting.Compat
open Lean Elab Command

/-- Select a command by the presence of a fully qualified declaration.
Only the selected branch is elaborated. Both branches must use syntax the host can parse. -/
syntax (name := ifDecl) "#if_decl " ident " then " command " else " command : command

elab_rules : command
  | `(#if_decl $name:ident then $yes:command else $no:command) => do
    elabCommand (if (← getEnv).contains name.getId then yes else no)

end SemanticHighlighting.Compat
