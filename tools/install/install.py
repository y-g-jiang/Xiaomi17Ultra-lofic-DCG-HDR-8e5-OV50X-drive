import argparse,subprocess,tarfile,time,shlex
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--adb',default='adb')
    ap.add_argument('--serial',required=True)
    ap.add_argument('--apk',type=Path,default=ROOT/'dist/JC_Camera.apk')
    a=ap.parse_args();adb=[a.adb,'-s',a.serial]
    files=[line.split('  ',1)[1] for line in (ROOT/'runtime.sha256').read_text().splitlines()]
    files+=['runtime.sha256','tools/install/install.sh']
    for name in files:
        if not (ROOT/name).is_file():raise FileNotFoundError(ROOT/name)
    if not a.apk.is_file():raise FileNotFoundError(a.apk)
    work=ROOT/'build/install';work.mkdir(parents=True,exist_ok=True)
    archive=work/'runtime.tar'
    with tarfile.open(archive,'w') as tar:
        for name in sorted(set(files)):tar.add(ROOT/name,arcname=name)
    remote='/data/local/tmp/jc-install-'+str(int(time.time()))
    def run(args):subprocess.run(adb+args,check=True)
    run(['shell','mkdir','-p',remote])
    run(['push',str(archive),remote+'/runtime.tar'])
    run(['push',str(a.apk),remote+'/JC_Camera.apk'])
    command='mkdir -p '+remote+'/runtime && tar -xf '+remote+'/runtime.tar -C '+remote+'/runtime && sh '+remote+'/runtime/tools/install/install.sh '+remote+'/JC_Camera.apk'
    run(['shell','su -M -c '+shlex.quote(command)])

if __name__=='__main__':main()
