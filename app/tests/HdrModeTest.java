package local.jc.mainraw;
import java.util.*;

public class HdrModeTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    public static void main(String[] args){
        Set<String> all=new HashSet<>(Arrays.asList(HdrMode.LOFIC,HdrMode.DCG,HdrMode.EXPOSURES));
        int[] caps={658949,3588,3076};
        check(HdrMode.pack(5,14,10)==0x0a0e05);check(HdrMode.pack(4,14,0)==0x0e04);check(HdrMode.pack(4,12,0)==0x0c04);
        List<HdrMode> profiles=HdrMode.supported(all,all,caps);check(profiles.size()==6);
        for(HdrMode p:profiles){
            if(p.parameters.containsKey(HdrMode.DCG)){
                int word=p.parameters.get(HdrMode.DCG);check(Arrays.stream(caps).anyMatch(x->x==word));
                check(p.parameters.get(HdrMode.EXPOSURES)==(p.mode==5?2:1));
            }
        }
        check(HdrMode.supported(Collections.emptySet(),all,caps).size()==1);
        check(HdrMode.supported(all,Collections.emptySet(),caps).size()==1);
        check(HdrMode.supported(all,all,new int[]{0x10101,0x0304}).size()==2);
        check(HdrMode.supported(all,all,null).size()==2);
        check(HdrMode.supported(all,all,new int[]{3588,3588}).size()==3);
        List<HdrMode> physical=HdrMode.supported(all,all,new int[]{3588,658949});
        check(physical.size()==5);for(HdrMode p:physical)check(p.longBits!=12);
        Set<String> noExposure=new HashSet<>(all);noExposure.remove(HdrMode.EXPOSURES);
        check(HdrMode.supported(all,noExposure,caps).size()==2);
        Set<String> noLofic=new HashSet<>(all);noLofic.remove(HdrMode.LOFIC);
        List<HdrMode> dcgOnly=HdrMode.supported(noLofic,noLofic,caps);check(dcgOnly.size()==4);
        for(HdrMode p:dcgOnly)check(!p.parameters.containsKey(HdrMode.LOFIC));
        boolean rejected=false;try{profiles.get(1).rejectConflicts(Collections.singleton(HdrMode.LOFIC));}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        rejected=false;try{profiles.get(1).rejectConflicts(Collections.singleton(HdrMode.DCG));}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        profiles.get(0).rejectConflicts(all);
        System.out.println("HDR mode encoding, exposure counts, capability gates, duplicates and conflicts: PASS");
    }
}
