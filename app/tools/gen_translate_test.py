"""Extracts the TRANSLATIONS table from MainActivity.java and runs it against
real console lines using plain javac (no Android deps), so the translation
rules can be verified without a phone.

Run on the VPS:
  python3 gen_translate_test.py /root/botctl4/src/com/dsh/botctl/MainActivity.java
"""
import re
import subprocess
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else "/root/botctl4/src/com/dsh/botctl/MainActivity.java"

src = open(SRC, encoding="utf-8").read()

m = re.search(r"TRANSLATIONS\s*=\s*\{(.*?)\n\s*\};", src, re.S)
if not m:
    print("!! could not locate TRANSLATIONS table")
    sys.exit(1)
pairs_body = m.group(1)

literals = re.findall(r'"((?:[^"\\]|\\.)*)"', pairs_body)
print("extracted %d string literals => %d rules" % (len(literals), len(literals) // 2))
if len(literals) % 2 != 0:
    print("!! odd number of literals - table may be malformed")
    sys.exit(1)

samples = [
    "Logged in as link_cle [906f391a-e1f3-46fb-a8a6-9d3ccf1dd543].",
    "Logging in link_cle...",
    "Connecting to 3c3u.org:25565...",
    "Connected to host.3c3u.org/202.189.16.6:25565!",
    "Server teleport 1 to: 8440256.51046974, 91.00000000, 11622372.95944670",
    "Server started.",
    "Starting server on 0.0.0.0:25565...",
    "Started LAN server broadcaster",
    "Netty NIO server started on port 8080",
    "WebUI plugin loaded! Access at http://localhost:8080",
    "Queue parser registered",
    "AuthBypasser packet handlers registered (rewriteOfflineUuid=true, bypassAuthenticateHello=true)",
    "Logged in with online account!",
    "Server enforces secure chat, chat signing enabled",
    "No custom chat schema found for server: 3c3u.org, setting one may be required for chats and whispers to parse correctly",
    "Player Logout In Visual Range",
    "[VisualRange] ||banshuang_4 entered visual range [245.72, 73.0, 201.13]",
    "||344813 drowned",
    "\u00ab||smkjmjh\u00bb \u6709\u4eba\u7ec4\u961f\u5417",
    "Connection disconnected: 223.104.86.243/223.104.86.243:50010 : Connection closed.",
    "Disconnected: Manual Disconnect",
    "AutoReconnecting in 5s",
    "Login Rate Limited.",
    "has passed the whitelist check with auth: CONTROLLER_OR_SPECTATOR",
    "tried to connect!",
    "Non-Whitelisted Login Blocked",
    "Steve was slain by Zombie",
    "Alex fell from a high place",
    "Herobrine tried to swim in lava",
    "Loading block connection mappings ...",
    "Reconfiguring...",
    "Online",
]

samples_java = ",\n            ".join('"%s"' % s.replace("\\", "\\\\").replace('"', '\\"')
                                    for s in samples)

java = """import java.util.regex.Pattern;

public class TranslateTest {
    static final String[] TRANSLATIONS = {%s
    };

    static Pattern[] P;
    static String[] R;

    static void init() {
        int n = TRANSLATIONS.length / 2;
        P = new Pattern[n];
        R = new String[n];
        for (int i = 0; i < n; i++) {
            try { P[i] = Pattern.compile(TRANSLATIONS[i*2]); }
            catch (Exception e) { P[i] = null; R[i] = ""; System.out.println("BAD REGEX: " + TRANSLATIONS[i*2]); }
            R[i] = TRANSLATIONS[i*2+1];
        }
    }

    static String translate(String msg) {
        String out = msg.replace("\\n", " / ").trim();
        for (int i = 0; i < P.length; i++) {
            if (P[i] == null) continue;
            try { out = P[i].matcher(out).replaceAll(R[i]); } catch (Exception e) {}
        }
        return out;
    }

    public static void main(String[] a) {
        init();
        String[] samples = {
            %s
        };
        int changed = 0;
        for (String s : samples) {
            String t = translate(s);
            if (!t.equals(s)) changed++;
            System.out.println("IN : " + s);
            System.out.println((t.equals(s) ? "OUT(untouched): " : "OUT: ") + t);
        }
        System.out.println("--- " + changed + "/" + samples.length + " lines translated, rules=" + P.length);
    }
}
""" % (pairs_body, samples_java)

open("/root/TranslateTest.java", "w", encoding="utf-8").write(java)

javac = "/usr/lib/jvm/java-17-openjdk-amd64/bin/javac"
java_bin = "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"

cp = subprocess.run([javac, "-encoding", "UTF-8", "-d", "/root/tt", "/root/TranslateTest.java"],
                    capture_output=True, text=True)
if cp.returncode != 0:
    print("javac failed:\n" + cp.stdout + cp.stderr)
    sys.exit(2)

run = subprocess.run([java_bin, "-cp", "/root/tt", "TranslateTest"],
                     capture_output=True, text=True)
print(run.stdout)
if run.stderr.strip():
    print("stderr: " + run.stderr[:2000])
