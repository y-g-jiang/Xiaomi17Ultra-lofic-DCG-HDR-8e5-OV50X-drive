from pathlib import Path
import subprocess,tempfile,unittest,json
ROOT=Path(__file__).resolve().parent
SOURCE=(ROOT/'assets/native_capture.sh').read_text(encoding='utf8')
OUT=ROOT.parent/'build/tests';OUT.mkdir(parents=True,exist_ok=True)
BASH=__import__('os').environ.get('JC_SHELL', 'C:/Program Files/Git/bin/sh.exe' if __import__('os').name=='nt' else 'sh')
NL=chr(10)
def function(name):
    return name+'() {'+SOURCE.split(name+'() {',1)[1].split(NL+'}'+NL,1)[0]+NL+'}'+NL
def action(name,next_name):
    return SOURCE.split(NL+name+')'+NL,1)[1].split(NL+' ;;'+NL+next_name+')',1)[0]
class Lifecycle(unittest.TestCase):
    def test_timed_out_log_reader_cannot_publish_partial_readiness(self):
        body='''
echo 42 > $D/provider
echo OLD_READY > $D/policy_preview.log
timeout() { echo '999 D checker_smartae.cpp Get_Algo_Output SmartAE_output:{algo_type:5,evlist_num:1,}'; return 137; }
'''+function('read_policy_snapshot')+'''
if read_policy_snapshot; then exit 70; fi
test ! -s $D/policy_preview.log
test "$(cat $D/controller.state)" = CONTROLLER_LOG_TIMEOUT
'''
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stdout+p.stderr)
    def controller(self,lines):
        return self.run_shell('cat > $D/policy_preview.log <<EOF'+NL+NL.join(lines)+NL+'EOF'+NL+function('controller_snapshot_ready')+'controller_snapshot_ready 1000 990')
    def test_controller_blocks_async_downgrade_and_requires_fresh_complete_state(self):
        normal='999.1 D PowerDataCapture: getAlgoControlExif:AlgoControlExif:(DSAC_LITE:d:l:l;)AlgoControlReason:([boardTemp:31666][batteryLevel:96][cpuPsi:9][cameraTempWare:0][batteryTemp:300])'
        self.assertEqual(self.controller([normal]).returncode,0)
        for line in [normal.replace('999.1','997.1'),normal.replace('[batteryTemp:300])',''),normal.replace('DSAC_LITE:d:l:l;','SE:d:b:b;'),normal.replace('DSAC_LITE:d:l:l;','HDR:b:d:d;'),normal.replace('DSAC_LITE:d:l:l;','SN:d:l:l;'),normal+')NoRegisterController:(SE:d:d:b;)']:
            p=self.controller([line]);self.assertNotEqual(p.returncode,0,p.stdout)
        self.assertNotEqual(self.controller([]).returncode,0)
        p=self.controller([normal,'999.5 I ThermalSettingManage: initMimdPolicySettings data : {"SE":2}'])
        self.assertNotEqual(p.returncode,0);self.assertIn('UPDATE_PENDING',p.stdout)
        self.assertEqual(self.controller([normal,'999.5 I ThermalSettingManage: initMimdPolicySettings data : {"SE":0}',normal.replace('999.1','999.7')]).returncode,0)
    def test_good_preview_cannot_override_blocked_controller(self):
        lines=[str(t)+' D checker_smartae.cpp:525 Get_Algo_Output SmartAE_output:{algo_type:5,evlist_num:1,}' for t in [999.1,999.2,999.3]]
        lines+=['999.4 D PowerDataCapture: getAlgoControlExif:AlgoControlExif:(SE:d:b:b;)AlgoControlReason:([boardTemp:50191][batteryTemp:511])']
        self.assertEqual(self.policy(lines).returncode,0)
        self.assertNotEqual(self.controller(lines).returncode,0)
    def test_settings_changed_after_policy_blocks_shutter(self):
        setup='touch $D/armed; echo 42 > $D/provider; echo 1 > $D/expected.iso; PROPS=iso'+NL
        for mutation in ['rm $D/armed','mkdir $D/finishing','echo 41 > $D/provider','echo 2 > $D/expected.iso']:
            p=self.run_shell(setup+'getprop() { if [ "$1" = init.svc_debug_pid.vendor.camera-provider ]; then echo 42; else echo 1; fi; }'+NL+mutation+NL+function('capture_settings_ready')+'capture_settings_ready')
            self.assertNotEqual(p.returncode,0,mutation)
    def policy(self,lines):
        return self.run_shell('cat > $D/policy_preview.log <<EOF'+NL+NL.join(lines)+NL+'EOF'+NL+function('policy_snapshot_ready')+'policy_snapshot_ready 1000 990')
    def test_policy_requires_three_fresh_one_frame_results(self):
        lines=[str(t)+' D checker_smartae.cpp:525 Get_Algo_Output SmartAE_output:{algo_type:5,evlist_num:1,}' for t in [999.1,999.2,999.3]]
        self.assertEqual(self.policy(lines).returncode,0)
        self.assertNotEqual(self.policy(lines[:2]).returncode,0)
        self.assertNotEqual(self.policy([x.replace('999.','997.') for x in lines]).returncode,0)
        self.assertNotEqual(self.policy(lines[:2]+[lines[2].replace('algo_type:5,evlist_num:1','algo_type:1,evlist_num:4')]).returncode,0)
        self.assertNotEqual(self.policy([]).returncode,0)
    def test_second_shutter_is_rejected(self):
        gate=action('capture','finish|restore').split(' mkdir ',1)[0]
        p=self.run_shell('touch $D/shutter_requested'+NL+gate+NL+'echo SHUTTER')
        self.assertNotEqual(p.returncode,0);self.assertNotIn('SHUTTER',p.stdout)
    def run_shell(self,body):
        with tempfile.TemporaryDirectory(dir=OUT,prefix='mock_') as d:
            pre=NL.join(['set -eu','D='+repr(Path(d).as_posix()),'ID=s1','BASE=$D','CAM=$D/camera','mkdir -p $CAM'])+NL
            p=subprocess.run([BASH,'-s'],input=pre+body,text=True,capture_output=True,timeout=10)
            return p
    def test_enumeration_requires_consecutive_complete_snapshots(self):
        body='''
sleep() { :; }
dumpsys() {
 n=$(cat $D/n 2>/dev/null || echo 0); n=$((n+1)); echo $n > $D/n
 case $n in 1|3) echo 'Number of camera devices: 4';; *) echo 'Number of camera devices: 9';; esac
 echo 'Number of normal camera devices: 2'
}
'''+function('wait_camera_enumeration')+'wait_camera_enumeration; test $(cat $D/n) = 5'
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stderr)
    def test_incomplete_enumeration_blocks(self):
        p=self.run_shell('sleep() { :; }; dumpsys() { echo partial; };'+NL+function('wait_camera_enumeration')+'wait_camera_enumeration')
        self.assertNotEqual(p.returncode,0);self.assertIn('enumeration incomplete',p.stdout)
    def test_wrong_route_rejected_before_shutter(self):
        gate=action('capture','finish|restore').split(' dumpsys -t 3 media.camera',1)[1].split(' expected_sensor=',1)[0]
        body='dumpsys() { echo "(Camera ID: 2, Client Package Name: com.android.camera,"; };'+NL+'dumpsys -t 3 media.camera'+gate+NL+'echo SHUTTER'
        p=self.run_shell(body);self.assertNotEqual(p.returncode,0);self.assertNotIn('SHUTTER',p.stdout)
    def test_enumeration_drains_provider_dump_without_sigpipe(self):
        body='''
sleep() { :; }
dumpsys() {
 echo 'Number of camera devices: 9'
 echo 'Number of normal camera devices: 2'
 # Much larger than a pipe buffer; a short reader would kill the producer.
 seq 1 50000 > $D/diagnostics
 cat $D/diagnostics || return 80
 echo FINISHED > $D/drained
}
'''+function('wait_camera_enumeration')+'''
wait_camera_enumeration
test -f $D/drained
test $(wc -l < $D/provider_dump.pending) = 50002
test $(wc -l < $D/provider_ready.pending) = 18
'''
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stdout+p.stderr)
    def test_restore_lock_suppresses_watchdog(self):
        body='''
touch $D/armed; echo 0 > $D/started; mkdir $D/finishing
sleep() { n=$(cat $D/n 2>/dev/null || echo 0); n=$((n+1)); echo $n > $D/n; if [ $n -ge 2 ]; then rm $D/armed; fi; }
getprop() { echo SHOULD_NOT_READ >&2; exit 88; }
PROPS=x
'''+action('watch','capture')
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stderr);self.assertNotIn('SHOULD_NOT_READ',p.stderr);self.assertNotIn('recovering',p.stdout)
    def test_restore_lock_acquired_during_property_read(self):
        body='''
touch $D/armed; echo 0 > $D/started; echo old > $D/provider
sleep() { n=$(cat $D/n 2>/dev/null || echo 0); n=$((n+1)); echo $n > $D/n; if [ $n -ge 2 ]; then rm $D/armed; fi; }
getprop() { mkdir -p $D/finishing; echo changed; }
PROPS=
'''+action('watch','capture')
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stderr);self.assertNotIn('recovering',p.stdout)
    def test_completed_manifest_does_not_scan_or_restore(self):
        body='''
touch $D/manifest.complete; echo ORIGINAL > $D/manifest
ACTION=finish
restore() { echo WRONG_RESTORE; exit 80; }
find() { echo WRONG_SCAN; exit 81; }
'''+action('finish|restore','retry_state')
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout.strip(),'ORIGINAL')
    def test_manifest_published_atomically(self):
        body='''
touch $D/before
echo first > $CAM/AllinOne_metadata.bin
ACTION=finish
restore() { :; }
sha() { sha256sum $1 | cut -d ' ' -f 1; }
'''+action('finish|restore','retry_state')+NL+'''
test -f $D/manifest.complete
test ! -f $D/manifest.pending
test $(wc -l < $D/manifest) = 1
'''
        p=self.run_shell(body);self.assertEqual(p.returncode,0,p.stderr)
    def test_retry_requires_proof_no_shutter(self):
        gate=action('retry_state','status')
        for setup,expected in [('touch $D/policy.blocked',0),
             ('touch $D/policy.blocked $D/shutter_requested',1),
             ('touch $D/policy.blocked $D/manifest.complete',1),
             ('touch $D/policy.blocked; mkdir $D/capturing',1),(':',1)]:
            p=self.run_shell(setup+NL+gate)
            self.assertEqual(p.returncode,expected,p.stdout+p.stderr)
if __name__=='__main__':
    r=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Lifecycle))
    (OUT/'lifecycle_tests.json').write_text(json.dumps({'tests':r.testsRun,'failures':len(r.failures),'errors':len(r.errors),'passed':r.wasSuccessful()},indent=2))
    raise SystemExit(not r.wasSuccessful())
