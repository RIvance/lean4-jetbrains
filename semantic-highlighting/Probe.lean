import Lean

/-- Machine-readable identity from the executable that will load the library. -/
def main : IO Unit :=
  IO.println <| Lean.Json.compress <| Lean.Json.mkObj [
    ("version", Lean.toJson Lean.versionString),
    ("githash", Lean.toJson Lean.githash)]
