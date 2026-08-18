import os
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else 'output/decompile'

for root, _, files in os.walk(path):
    for name in files:
        if not name.endswith(('.c', '.cpp', '.h')):
            continue
        p = os.path.join(root, name)
        try:
            data = open(p, 'r', errors='ignore').read()
            data = re.sub(r'/\*.*?\*/', '', data, flags=re.S)
            data = re.sub(r'\n\s*\n\s*\n+', '\n\n', data)
            data = data.replace('__int64', 'int64_t')
            data = data.replace('__int32', 'int32_t')
            open(p, 'w').write(data.strip() + '\n')
        except Exception:
            pass
