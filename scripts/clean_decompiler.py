import os
import re
import shutil
import sys

SOURCE_EXTENSIONS = ('.c', '.cpp', '.h', '.hpp')

src = sys.argv[1] if len(sys.argv) > 1 else 'output/decompile/raw'
dst = sys.argv[2] if len(sys.argv) > 2 else 'output/decompile/clean'
compat_source = os.path.join(os.path.dirname(__file__), 'ghidra_compat.h')

if os.path.abspath(src) != os.path.abspath(dst):
    if os.path.exists(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

for root, dirs, files in os.walk(dst):
    dirs.sort()
    source_files = [name for name in files if name.endswith(('.c', '.cpp'))]
    if source_files and os.path.isfile(compat_source):
        shutil.copy2(compat_source, os.path.join(root, 'ghidra_compat.h'))

    for name in sorted(files):
        if not name.endswith(SOURCE_EXTENSIONS):
            continue
        path = os.path.join(root, name)
        try:
            with open(path, 'r', encoding='utf-8', errors='ignore') as fh:
                data = fh.read()

            data = re.sub(r'/\*(?! =====).*?\*/', '', data, flags=re.S)
            data = data.replace('__int64', 'int64_t')
            data = data.replace('__int32', 'int32_t')
            data = data.replace('__int16', 'int16_t')
            data = data.replace('__int8', 'int8_t')
            data = re.sub(r'[ \t]+\n', '\n', data)
            data = re.sub(r'\n{4,}', '\n\n\n', data)

            if name.endswith(('.c', '.cpp')) and '#include "ghidra_compat.h"' not in data:
                data = '#include "ghidra_compat.h"\n' + data.lstrip()

            data = data.strip() + '\n'

            with open(path, 'w', encoding='utf-8', newline='\n') as fh:
                fh.write(data)
        except Exception as exc:
            print(f'warning: could not clean {path}: {exc}', file=sys.stderr)
