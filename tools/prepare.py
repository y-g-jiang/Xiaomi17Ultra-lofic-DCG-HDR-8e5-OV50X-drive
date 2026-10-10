import argparse
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stock', type=Path, required=True)
    ap.add_argument('--output', type=Path, default=ROOT/'build/driver')
    a = ap.parse_args()
    sensor = load('sensor', ROOT/'tools/lofic_unity/patch_sensor.py')
    module = load('module', ROOT/'tools/native50_qbayer/patch_module.py')
    prefix = 'com.qti.'
    device = 'nezha_semco_ovx10500u_wide_i'
    outputs = {
        'unity.so': sensor.patch((a.stock/(prefix+'sensor.'+device+'.so')).read_bytes()),
        'sensormodule.bin': module.patch_module((a.stock/(prefix+'sensormodule.'+device+'.bin')).read_bytes()),
        'camx.txt': module.patch_camx((a.stock/'camxoverridesettings.txt').read_bytes()),
    }
    references = {'unity.so':'app/assets/lofic_mode5_unity.so',
                  'sensormodule.bin':'app/assets/pair_mode0_module.bin',
                  'camx.txt':'tools/native50_qbayer/camx.txt'}
    for name, data in outputs.items():
        if data != (ROOT/references[name]).read_bytes():
            raise ValueError(name)
    a.output.mkdir(parents=True, exist_ok=True)
    for name, data in outputs.items():
        (a.output/name).write_bytes(data)
        print(name)

if __name__ == '__main__':
    main()
