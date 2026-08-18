# Automated `.so` reverse engineering

`run_reverse.sh` recursively finds every ELF shared object (`*.so`) in the repository and produces a self-contained analysis directory for each file.

The pipeline combines conventional ELF tooling (`readelf`, `nm`, `objdump`, GNU `strings`) with Ghidra's headless analyzer/decompiler. `postprocess.py` then creates normalized `recovered.c` and `recovered.cpp` views, adds a small Ghidra compatibility header, runs `clang-format`, and records `clang`/`clang++` syntax diagnostics without discarding the raw decompiler output.

## Output

- `input/`: original `.so`
- `static/`: hashes, ELF metadata, symbols/imports/exports, full disassembly, ASCII/UTF-16/UTF-32 string dumps
- `ghidra/functions/`: one pseudo-C file per recovered function
- `ghidra/decompiled.c`: combined raw Ghidra output
- `ghidra/symbols.tsv` and `ghidra/functions.tsv`: searchable symbol/function indexes
- `ghidra/cleaned/recovered.c` and `.cpp`: normalized best-effort pseudo-source
- `ghidra/cleaned/compile_check.txt`: compiler diagnostics
- `logs/`: headless analysis logs and status

## Important limitation

A native binary does not contain enough information to reconstruct the exact original C/C++ project. Optimized or stripped code permanently loses identifiers, comments, macros, template structure and sometimes high-level type information. The cleaned output is therefore intended for analysis and manual reconstruction; a successful decompile does not imply byte-for-byte source recovery or guaranteed compilation.
