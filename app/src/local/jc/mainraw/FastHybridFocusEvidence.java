package local.jc.mainraw;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** This device's audited factory position; not an optical displacement measurement. */
final class FastHybridFocusEvidence {
    static boolean isMainRoute(String logicalId, String physicalId) {
        return ("2".equals(logicalId) && (physicalId == null || physicalId.isEmpty()))
                || ("0".equals(logicalId) && "2".equals(physicalId));
    }

    static boolean matches(String log, long sinceWallMs) {
        int overrides = 0, targets = 0;
        Pattern epoch = Pattern.compile("^\\s*(\\d+\\.\\d+)");
        Pattern step = Pattern.compile("ManualAF Override lens position\\(Step\\):\\s*(\\d+)");
        Pattern target = Pattern.compile("TargetPosition:\\s*(\\d+)\\(DAC:(\\d+)\\)");
        for (String line : log.split("\n")) {
            Matcher time = epoch.matcher(line);
            if (!time.find() || Double.parseDouble(time.group(1)) * 1000 < sinceWallMs) continue;
            if (!line.contains("Actuator[0]")) continue;
            Matcher s = step.matcher(line), t = target.matcher(line);
            if (s.find()) { if (!s.group(1).equals("824")) return false; overrides++; }
            if (t.find()) {
                if (!t.group(1).equals("824") || !t.group(2).equals("552")) return false;
                targets++;
            }
        }
        return overrides >= 3 && targets >= 1;
    }
}
