import Lean

def main : IO Unit := do
  let paths ← Lean.getSrcSearchPath
  IO.println (Lean.toJson (paths.map (·.toString))).compress
