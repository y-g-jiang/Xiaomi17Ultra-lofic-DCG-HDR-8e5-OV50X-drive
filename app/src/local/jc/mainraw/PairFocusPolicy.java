package local.jc.mainraw;

/** Compare actual Camera2 lens distance with the locked preview result. */
final class PairFocusPolicy {
    static int stableDac(String log,long sinceWallMs) {
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("^\\s*(\\d+\\.\\d+).*Actuator\\[0\\].*TargetPosition:\\s*\\d+\\(DAC:(\\d+)\\)");
        int dac=-1,count=0;long first=0,last=0;
        for(String line:log.split("\n")){java.util.regex.Matcher m=pattern.matcher(line);if(!m.find())continue;
            long at=(long)(Double.parseDouble(m.group(1))*1000);if(at<sinceWallMs)continue;
            int value=Integer.parseInt(m.group(2));if(value<0||value>4095)return -1;
            if(dac!=-1&&value!=dac)return -1;dac=value;if(count++==0)first=at;last=at;
        }
        return count>=3&&last-first>=400?dac:-1;
    }
    static boolean matches(float requested,float actual){
        return Float.isFinite(requested)&&Float.isFinite(actual)&&requested>=0&&actual>=0
                && Math.abs(requested-actual)<=Math.max(.01f,requested*.01f);
    }
}
