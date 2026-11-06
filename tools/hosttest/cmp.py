import json, subprocess, sys
orig = json.load(open(sys.argv[1]))
res = json.loads(subprocess.check_output(['node', '/home/claude/rig/abcnotes.js', sys.argv[2]], cwd='/home/claude/rig'))
tr = res['tracks']
diffs = 0; total = 0
for v, notes in enumerate(orig):
    a = sorted(tuple(round(x, 3) for x in n) for n in notes)
    b = sorted(tuple(round(x, 3) for x in n) for n in (tr[v] if v < len(tr) else []))
    total += len(a)
    if a != b:
        sa, sb = set(a), set(b)
        d = len(sa ^ sb); diffs += d
        print(f"  voice {v}: {len(a)} vs abcjs {len(b)}; e.g. only orig {sorted(sa-sb)[:3]} only abcjs {sorted(sb-sa)[:3]}")
print(("IDENTICAL" if diffs == 0 else f"DIFFS {diffs}") + f" ({total} notes, abcjs warnings {res['warnings']})")
