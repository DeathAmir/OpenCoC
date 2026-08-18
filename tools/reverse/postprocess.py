#!/usr/bin/env python3
import argparse
import pathlib
import re
import shutil
import subprocess

COMPAT_HEADER = r'''#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

typedef uint8_t byte;
typedef uint8_t ubyte;
typedef int8_t sbyte;
typedef uint16_t ushort;
typedef int16_t shortint;
typedef uint32_t uint;
typedef int32_t int32;
typedef uint64_t ulonglong;
typedef int64_t longlong;
typedef uintptr_t uintptr;
typedef intptr_t intptr;

typedef uint8_t undefined;
typedef uint8_t undefined1;
typedef uint16_t undefined2;
typedef uint32_t undefined4;
typedef uint64_t undefined8;
typedef unsigned __int128 undefined16;

#ifndef __cdecl
#define __cdecl
#endif
#ifndef __stdcall
#define __stdcall
#endif
#ifndef __fastcall
#define __fastcall
#endif
#ifndef __thiscall
#define __thiscall
#endif
#ifndef __noreturn
#define __noreturn __attribute__((noreturn))
#endif
'''

WARNING_RE = re.compile(r"/\*\s*WARNING:.*?\*/", re.IGNORECASE | re.DOTALL)


def run_command(cmd, cwd, timeout=180):
    try:
        p = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, timeout=timeout)
        return p.returncode, p.stdout + p.stderr
    except subprocess.TimeoutExpired as ex:
        stdout = ex.stdout or ""
        stderr = ex.stderr or ""
        if isinstance(stdout, bytes):
            stdout = stdout.decode(errors="replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode(errors="replace")
        return 124, stdout + stderr + "\nTIMEOUT\n"
    except FileNotFoundError as ex:
        return 127, str(ex) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ghidra_dir", type=pathlib.Path)
    args = parser.parse_args()

    ghidra_dir = args.ghidra_dir.resolve()
    src = ghidra_dir / "decompiled.c"
    clean_dir = ghidra_dir / "cleaned"
    clean_dir.mkdir(parents=True, exist_ok=True)

    compat = clean_dir / "ghidra_compat.h"
    compat.write_text(COMPAT_HEADER, encoding="utf-8")

    if not src.exists():
        (clean_dir / "README.txt").write_text("No decompiled.c was produced by Ghidra. See ../decompile_errors.tsv and ../ghidra.log.\n", encoding="utf-8")
        return 0

    raw = src.read_text(encoding="utf-8", errors="replace")
    warnings = WARNING_RE.findall(raw)
    cleaned = WARNING_RE.sub("", raw)
    cleaned = cleaned.replace("\r\n", "\n")
    cleaned = re.sub(r"[ \t]+\n", "\n", cleaned)
    cleaned = re.sub(r"\n{4,}", "\n\n\n", cleaned)

    (clean_dir / "ghidra_warnings.txt").write_text("\n\n".join(warnings) + ("\n" if warnings else ""), encoding="utf-8")

    banner = (
        "/* Best-effort recovered pseudo-source generated from a binary by Ghidra.\n"
        " * It is NOT guaranteed to match the original source or to compile without manual repair. */\n"
        '#include "ghidra_compat.h"\n\n'
    )
    c_path = clean_dir / "recovered.c"
    cpp_path = clean_dir / "recovered.cpp"
    c_path.write_text(banner + cleaned, encoding="utf-8")
    cpp_path.write_text(banner + cleaned, encoding="utf-8")

    formatter = shutil.which("clang-format")
    if formatter:
        for path in (c_path, cpp_path):
            rc, output = run_command([formatter, "-i", str(path)], clean_dir, timeout=180)
            if rc != 0:
                with (clean_dir / "format_errors.txt").open("a", encoding="utf-8") as fh:
                    fh.write(f"{path.name}: rc={rc}\n{output}\n")

    checks = []
    if shutil.which("clang"):
        checks.append(("C", ["clang", "-std=gnu11", "-fsyntax-only", "-ferror-limit=500", "-I.", c_path.name]))
    if shutil.which("clang++"):
        checks.append(("C++", ["clang++", "-std=gnu++17", "-fsyntax-only", "-ferror-limit=500", "-I.", cpp_path.name]))

    report = []
    for label, cmd in checks:
        rc, output = run_command(cmd, clean_dir, timeout=240)
        report.append(f"===== {label} syntax check: exit={rc} =====\n{output}")

    if not checks:
        report.append("clang/clang++ not found; syntax check skipped.\n")

    (clean_dir / "compile_check.txt").write_text("\n".join(report), encoding="utf-8")
    (clean_dir / "README.txt").write_text(
        "recovered.c and recovered.cpp are normalized best-effort pseudo-source.\n"
        "ghidra_compat.h provides common Ghidra placeholder types/calling-convention macros.\n"
        "compile_check.txt records syntax diagnostics instead of failing the whole reverse-engineering job.\n"
        "Manual semantic reconstruction is still required for exact, buildable original source.\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
