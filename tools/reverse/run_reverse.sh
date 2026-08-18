#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <ghidra-home> <output-dir>" >&2
  exit 2
fi

GHIDRA_HOME="$(cd "$1" && pwd)"
OUT_ROOT="$2"
ROOT="$(pwd)"
SCRIPT_DIR="$ROOT/tools/reverse/ghidra"
POSTPROCESS="$ROOT/tools/reverse/postprocess.py"
mkdir -p "$OUT_ROOT"
OUT_ROOT="$(cd "$OUT_ROOT" && pwd)"

ANALYZE="$GHIDRA_HOME/support/analyzeHeadless"
if [[ ! -x "$ANALYZE" ]]; then
  echo "analyzeHeadless not found: $ANALYZE" >&2
  exit 3
fi

mapfile -d '' SO_FILES < <(find "$ROOT" -type f -name '*.so' -not -path '*/.git/*' -print0 | sort -z)
if [[ ${#SO_FILES[@]} -eq 0 ]]; then
  echo "No .so files found" | tee "$OUT_ROOT/NO_SO_FILES.txt"
  exit 4
fi

printf 'relative_path\tsha256\tfile_description\tghidra_status\n' > "$OUT_ROOT/index.tsv"

for so in "${SO_FILES[@]}"; do
  rel="${so#$ROOT/}"
  id="$(printf '%s' "$rel" | sed -E 's#[^A-Za-z0-9._-]+#_#g; s#\.so$##')"
  out="$OUT_ROOT/$id"
  mkdir -p "$out/input" "$out/static" "$out/ghidra" "$out/logs" "$out/project"
  cp -f "$so" "$out/input/$(basename "$so")"

  sha="$(sha256sum "$so" | awk '{print $1}')"
  desc="$(file -b "$so" | tr '\t\r\n' '   ')"
  printf '%s\n' "$sha" > "$out/static/sha256.txt"
  printf '%s\n' "$desc" > "$out/static/file.txt"

  (readelf -W -a "$so" > "$out/static/readelf-all.txt" 2>&1) || true
  (readelf -W --dyn-syms "$so" > "$out/static/dynamic-symbols.txt" 2>&1) || true
  (readelf -W -r "$so" > "$out/static/relocations.txt" 2>&1) || true
  (nm -a -C "$so" > "$out/static/nm-all.txt" 2>&1) || true
  (nm -D -C "$so" > "$out/static/nm-dynamic.txt" 2>&1) || true
  (nm -D -C --defined-only "$so" > "$out/static/exports.txt" 2>&1) || true
  (objdump -x -C "$so" > "$out/static/objdump-headers.txt" 2>&1) || true
  (objdump -D -C "$so" > "$out/static/disassembly.txt" 2>&1) || true
  (strings -a -t x -n 3 "$so" > "$out/static/strings-ascii.txt" 2>&1) || true
  (strings -a -el -t x -n 3 "$so" > "$out/static/strings-utf16le.txt" 2>&1) || true
  (strings -a -eb -t x -n 3 "$so" > "$out/static/strings-utf16be.txt" 2>&1) || true
  (strings -a -eL -t x -n 3 "$so" > "$out/static/strings-utf32le.txt" 2>&1) || true
  (strings -a -eB -t x -n 3 "$so" > "$out/static/strings-utf32be.txt" 2>&1) || true

  project_name="proj_${id//[^A-Za-z0-9_]/_}"
  set +e
  timeout --signal=TERM 45m "$ANALYZE" "$out/project" "$project_name" \
    -import "$so" \
    -overwrite \
    -scriptPath "$SCRIPT_DIR" \
    -postScript ExportDecompile.java "$out/ghidra" \
    > "$out/logs/ghidra.log" 2>&1
  ghidra_rc=$?
  set -e

  if [[ $ghidra_rc -eq 0 ]]; then
    ghidra_status="ok"
  elif [[ $ghidra_rc -eq 124 ]]; then
    ghidra_status="timeout"
  else
    ghidra_status="failed:$ghidra_rc"
  fi
  printf '%s\n' "$ghidra_status" > "$out/logs/ghidra-status.txt"

  python3 "$POSTPROCESS" "$out/ghidra" > "$out/logs/postprocess.log" 2>&1 || true
  rm -rf "$out/project"

  printf '%s\t%s\t%s\t%s\n' "$rel" "$sha" "$desc" "$ghidra_status" >> "$OUT_ROOT/index.tsv"
done

cat > "$OUT_ROOT/README.txt" <<'TXT'
This archive contains automated reverse-engineering output for every .so found in the repository.
For each library:
  input/        original analyzed binary
  static/       hashes, ELF metadata, symbols, exports, disassembly, ASCII/UTF string dumps
  ghidra/       Ghidra symbols, function index, per-function decompilation and combined pseudo-C
  ghidra/cleaned/ best-effort normalized recovered.c/recovered.cpp plus compile diagnostics
  logs/         Ghidra and post-processing logs

Decompiler output is reconstructed pseudo-source, not the original source code. Names, types, control flow,
C++ class structure, templates, macros and comments may be incomplete or wrong, especially in stripped/optimized binaries.
TXT
