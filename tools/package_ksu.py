import argparse,hashlib,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--apk',type=Path,default=ROOT/'dist/JC_Camera.apk')
    ap.add_argument('--output',type=Path,default=ROOT/'dist/JC_Camera_KSU.zip')
    a=ap.parse_args()
    if not a.apk.is_file():raise FileNotFoundError(a.apk)
    rows=(ROOT/'runtime.sha256').read_text().splitlines()
    for line in rows:
        sha,name=line.split('  ',1)
        assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==sha,name
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted((ROOT/'ksu').rglob('*')):
            if p.is_file():z.write(p,p.relative_to(ROOT/'ksu').as_posix())
        for line in rows:
            sha,name=line.split('  ',1);z.write(ROOT/name,'runtime/'+name)
        rows.append(hashlib.sha256(a.apk.read_bytes()).hexdigest()+'  dist/JC_Camera.apk')
        z.writestr('runtime/runtime.sha256','\n'.join(rows)+'\n')
        z.write(a.apk,'runtime/dist/JC_Camera.apk')
    print(a.output,hashlib.sha256(a.output.read_bytes()).hexdigest())

if __name__=='__main__':main()
