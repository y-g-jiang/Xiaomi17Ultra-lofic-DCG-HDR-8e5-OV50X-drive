import argparse,hashlib,json,os,shutil,subprocess,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent

def run(args):
    subprocess.run([str(x) for x in args],check=True)

def tool(directory,name):
    p=directory/(name+('.exe' if os.name=='nt' else ''))
    if not p.is_file():raise FileNotFoundError(p)
    return p

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--sdk',type=Path,default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'))
    ap.add_argument('--jdk',type=Path,default=os.environ.get('JAVA_HOME'))
    ap.add_argument('--build-tools',type=Path)
    ap.add_argument('--android-jar',type=Path)
    ap.add_argument('--output',type=Path,default=ROOT.parent/'dist/JC_Camera.apk')
    ap.add_argument('--build-dir',type=Path,default=ROOT.parent/'build/app')
    ap.add_argument('--native',type=Path,default=ROOT/'native/prebuilt/arm64-v8a/libjcrawsurface.so')
    ap.add_argument('--keystore',type=Path)
    ap.add_argument('--key-alias',default='androiddebugkey')
    a=ap.parse_args()
    if not a.sdk or not a.jdk:ap.error('--sdk and --jdk are required unless configured in the environment')
    sdk=a.sdk.resolve();jdk=a.jdk.resolve()/'bin';b=a.build_dir.resolve();b.mkdir(parents=True,exist_ok=True)
    bt=a.build_tools or max((sdk/'build-tools').iterdir(),key=lambda p:tuple(int(x) for x in p.name.split('.')))
    android=a.android_jar or sdk/'platforms/android-35/android.jar'
    for p in (android,a.native):
        if not p.is_file():raise FileNotFoundError(p)
    expected='2340366bcdda0d2b8c4fe179e75c443c105e616fa503975c148dfe18d2e9f35c'
    if a.native==ROOT/'native/prebuilt/arm64-v8a/libjcrawsurface.so':
        assert hashlib.sha256(a.native.read_bytes()).hexdigest()==expected
    for n in ('classes','dex'):
        p=b/n
        if p.exists():shutil.rmtree(p)
        p.mkdir()
    run([tool(bt,'aapt2'),'link','-I',android,'--manifest',ROOT/'AndroidManifest.xml','-o',b/'base.apk'])
    sources=sorted((ROOT/'src').rglob('*.java'))
    run([tool(jdk,'javac'),'-encoding','UTF-8','-source','8','-target','8','-classpath',android,'-d',b/'classes',*sources])
    run([tool(jdk,'jar'),'cf',b/'classes.jar','-C',b/'classes','.'])
    run([tool(jdk,'java'),'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','29','--output',b/'dex',b/'classes.jar'])
    shutil.copy2(b/'base.apk',b/'unsigned.apk')
    with zipfile.ZipFile(b/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
        z.write(a.native,'lib/arm64-v8a/libjcrawsurface.so',compress_type=zipfile.ZIP_STORED)
        for p in sorted((b/'dex').glob('*.dex')):z.write(p,p.name)
        for p in sorted((ROOT/'assets').rglob('*')):
            if p.is_file():z.write(p,'assets/'+p.relative_to(ROOT/'assets').as_posix())
    run([tool(bt,'zipalign'),'-f','-p','4',b/'unsigned.apk',b/'aligned.apk'])
    key=a.keystore or b/'debug.keystore'
    password=os.environ.get('JC_KEYSTORE_PASSWORD','android')
    if not key.exists():
        if a.keystore:raise FileNotFoundError(key)
        run([tool(jdk,'keytool'),'-genkeypair','-keystore',key,'-storepass',password,'-keypass',password,
             '-alias',a.key_alias,'-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Android Debug','-noprompt'])
    a.output.parent.mkdir(parents=True,exist_ok=True)
    os.environ['JC_SIGNING_PASSWORD']=password
    run([tool(jdk,'java'),'-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias',a.key_alias,
         '--ks-pass','env:JC_SIGNING_PASSWORD','--key-pass','env:JC_SIGNING_PASSWORD','--out',a.output,b/'aligned.apk'])
    run([tool(jdk,'java'),'-jar',bt/'lib/apksigner.jar','verify','--verbose',a.output])
    print(json.dumps(dict(apk=str(a.output),sha256=hashlib.sha256(a.output.read_bytes()).hexdigest(),bytes=a.output.stat().st_size)))

if __name__=='__main__':main()
