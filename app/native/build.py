import argparse,os,subprocess
from pathlib import Path

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--ndk',required=True,type=Path)
    ap.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[2]/'build/native/arm64-v8a/libjcrawsurface.so')
    a=ap.parse_args();root=Path(__file__).resolve().parent
    host='windows-x86_64' if os.name=='nt' else ('darwin-x86_64' if __import__('sys').platform=='darwin' else 'linux-x86_64')
    llvm=a.ndk/'toolchains/llvm/prebuilt'/host
    clang=llvm/'bin'/('clang.exe' if os.name=='nt' else 'clang')
    a.output.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(clang),'--target=aarch64-linux-android29','--sysroot='+str(llvm/'sysroot'),
        '-shared','-fPIC','-O2','-Wl,-z,max-page-size=16384','-Wl,-z,defs',str(root/'raw_surface_probe.c'),
        '-o',str(a.output),'-landroid','-lnativewindow','-lc'],check=True)

if __name__=='__main__':main()
