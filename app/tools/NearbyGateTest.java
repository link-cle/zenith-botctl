import com.dsh.botctl.Nearby;
import com.dsh.botctl.NotifyGate;

/**
 * 用真实服务器日志原文做的回归测试（不需要 Android、不需要真机）。
 *
 * 覆盖的坑（都是实测踩出来的）：
 *  1. Module 日志用科学计数法：1.3111833266436212E7，只认 [0-9.] 会把 Z 读成 1
 *  2. 同一事件被 Module 和 Terminal 两个 logger 各发一遍，坐标还差 1（1210371.9 vs 1210371）
 *  3. 玩家在视野边缘每 2 秒 enter/leave 一次，坐标完全没变
 *
 * 期望：整段序列只产生 3 次通知。
 *
 * 跑法（在 app/ 目录）：
 *   mkdir -p /tmp/t && javac -encoding UTF-8 -d /tmp/t \
 *       src/com/dsh/botctl/Nearby.java src/com/dsh/botctl/NotifyGate.java tools/NearbyGateTest.java \
 *     && java -cp /tmp/t NearbyGateTest
 */
public class NearbyGateTest {
    static Nearby n = new Nearby();
    static NotifyGate gate = new NotifyGate();
    static int notified = 0;

    static void feed(String tag, String msg) {
        Nearby.Event ev = n.feed(msg);
        if (ev == null || ev.type == Nearby.FRAGMENT) return;
        boolean go = gate.shouldNotify(ev);
        String type = ev.type == Nearby.ENTER ? "进入" : (ev.type == Nearby.LEAVE ? "离开" : "下线");
        System.out.println("  [" + tag + "] " + type + " " + ev.name
                + (ev.hasCoords() ? " " + ev.coordsText() : "")
                + (go ? "  -> 通知" : "  -> 静默"));
        if (go) notified++;
    }

    public static void main(String[] a) {
        feed("Module", "[VisualRange] ||ExamplePlayer entered visual range [1210651.255154248, 128.0625, 1.3111441752915835E7]");
        feed("Terminal", "\nPlayer In Visual Range\n\nPlayer Name\n\n||ExamplePlayer\nPlayer UUID\n11111111 (https://namemc.com/profile/11111111)\nCoordinates\n[1210651, 128, 13111441]");
        feed("Module", "[VisualRange] ||ExamplePlayer left visual range [1210728.22805671, 128.0625, 1.3111450498368012E7]");
        feed("Terminal", "\nPlayer Left Visual Range\n\nPlayer Name\n\n||ExamplePlayer\nPlayer UUID\n11111111 (https://namemc.com/profile/11111111)\nCoordinates\n[1210728, 128, 13111450]");

        for (int i = 0; i < 3; i++) {
            feed("Module", "[VisualRange] TestPlayer_ entered visual range [1210371." + (90032784 + i) + ", 70.0, 1.3111833266436212E7]");
            feed("Terminal", "\nPlayer In Visual Range\n\nPlayer Name\n\nTestPlayer_\nPlayer UUID\n66666666 (https://namemc.com/profile/66666666)\nCoordinates\n[1210371, 70, 13111833]");
            feed("Module", "[VisualRange] TestPlayer_ left visual range [1210371.863519076, 70.0, 1.3111833165464535E7]");
            feed("Terminal", "\nPlayer Left Visual Range\n\nPlayer Name\n\nTestPlayer_\nPlayer UUID\n66666666 (https://namemc.com/profile/66666666)\nCoordinates\n[1210371, 70, 13111833]");
        }

        feed("Module", "[VisualRange] TestPlayer_ entered visual range [1210380.2, 70.0, 1.3111840166436212E7]");
        feed("Terminal", "\nPlayer In Visual Range\n\nPlayer Name\n\nTestPlayer_\nPlayer UUID\n66666666 (https://namemc.com/profile/66666666)\nCoordinates\n[1210380, 70, 13111840]");

        System.out.println("\n总计通知 " + notified + " 次（期望 3）");
        if (notified != 3) {
            System.out.println("!!! 回归失败");
            System.exit(1);
        }
        System.out.println("回归通过");
    }
}