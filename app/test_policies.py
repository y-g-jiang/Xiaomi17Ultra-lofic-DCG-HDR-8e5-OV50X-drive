import argparse
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jdk', type=Path, required=True)
    ap.add_argument('--android-jar', type=Path, required=True)
    a = ap.parse_args()
    suffix = '.exe' if os.name=='nt' else ''
    java = a.jdk/'bin'/('java'+suffix)
    javac = a.jdk/'bin'/('javac'+suffix)
    out = ROOT/'build/tests/classes'
    out.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join([str(ROOT/'build/app/classes'), str(a.android_jar)])
    sources = sorted((ROOT/'app/tests').glob('*.java'))
    subprocess.run([str(javac), '-encoding','UTF-8','-cp',cp,'-d',str(out),
                    *map(str,sources)], check=True)
    needs_capture = {'NativeRawPhoneTest','NativeRawTest','FastHybridPipelineTest','FastHybridSensorMetadataTest'}
    count = 0
    for p in sources:
        if not p.stem.endswith('Test') or p.stem in needs_capture:
            continue
        match = re.search(r'package\s+([\w.]+)\s*;', p.read_text(encoding='utf8'))
        name = (match.group(1)+'.' if match else '')+p.stem
        subprocess.run([str(java),'-Djava.io.tmpdir='+str(out.parent),'-cp',
                        str(out)+os.pathsep+cp,name], check=True)
        count += 1
    print('PASS', count)

if __name__=='__main__':
    main()
