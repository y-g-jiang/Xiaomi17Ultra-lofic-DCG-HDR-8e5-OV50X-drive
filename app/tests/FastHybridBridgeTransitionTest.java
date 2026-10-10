package local.jc.mainraw;

import java.io.*;
import java.util.concurrent.TimeUnit;

public class FastHybridBridgeTransitionTest {
    static class Fake extends Process {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final InputStream received;
        boolean destroyed, finishes = true;
        int code;
        Fake(String response) { received = new ByteArrayInputStream(response.getBytes()); }
        public OutputStream getOutputStream() { return sent; }
        public InputStream getInputStream() { return received; }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() throws InterruptedException { if (!finishes) Thread.sleep(5000); return code; }
        public boolean waitFor(long n, TimeUnit u) { return finishes; }
        public int exitValue() { return code; }
        public void destroy() { destroyed = true; }
    }
    interface Checked { void run() throws Exception; }
    static void rejects(Checked f) throws Exception {
        try { f.run(); } catch (IOException expected) { return; }
        throw new AssertionError("accepted invalid transition");
    }
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Fake good = new Fake("READY\n0\n0\n0\n");
        try (FastHybridBridgeTransition t = new FastHybridBridgeTransition(good)) {
            check(good.sent.size() == 0);
            check(t.apply().equals("0\n0\n0"));
            check(good.sent.toString().equals("GO\n"));
            rejects(t::apply);
            check(good.sent.toString().equals("GO\n"));
        }
        check(good.destroyed);
        Fake idle = new Fake("READY\n");
        new FastHybridBridgeTransition(idle).close();
        check(idle.destroyed && idle.sent.size() == 0);
        Fake badReady = new Fake("BAD\n");
        rejects(() -> new FastHybridBridgeTransition(badReady));
        check(badReady.destroyed && badReady.sent.size() == 0);
        for (String response : new String[]{"0\n1\n0\n", "0\n0\n", "0\n0\n0\nextra\n"}) {
            try (FastHybridBridgeTransition t = new FastHybridBridgeTransition(new Fake("READY\n" + response))) {
                rejects(t::apply);
            }
        }
        Fake failed = new Fake("READY\n0\n0\n0\n"); failed.code = 1;
        try (FastHybridBridgeTransition t = new FastHybridBridgeTransition(failed)) { rejects(t::apply); }
        Fake stuck = new Fake("READY\n"); stuck.finishes = false;
        try (FastHybridBridgeTransition t = new FastHybridBridgeTransition(stuck)) { rejects(t::apply); }
        check(stuck.destroyed);
        System.out.println("Bridge transition checks passed");
    }
}
