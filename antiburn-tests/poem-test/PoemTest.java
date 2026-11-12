import io.github.wumisc.antiburn.PoemItem;
import io.github.wumisc.antiburn.PoemLibrary;
import io.github.wumisc.antiburn.PoemMotion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure-JVM checks for poem library, playback order, expression parsing and LUT interpolation. */
public class PoemTest {
    private static int pass, fail;
    private static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + "  " + detail); }
    }

    public static void main(String[] args) {
        List<PoemItem> defaults = PoemLibrary.defaults();
        check(defaults.size() == 6, "六首默认诗", "size=" + defaults.size());
        check(PoemItem.listFromJson(PoemItem.listToJson(defaults), PoemItem.ORIGIN_LOCAL).size() == 6,
                "诗库 JSON 往返", "");
        check(PoemLibrary.local("[]").isEmpty(), "显式空库保持为空", "");
        check(PoemLibrary.mergeEnabled("[]", "[{\"text\":\"联网\",\"author\":\"A\",\"source\":\"S\",\"enabled\":true}]").size() == 1,
                "本地与联网启用项合并", "");

        List<PoemItem> seq = new ArrayList<>(defaults);
        PoemLibrary.Playback ordered = new PoemLibrary.Playback(seq, PoemLibrary.ORDER_SEQUENTIAL, 42);
        check(ordered.next() == seq.get(0), "顺序模式从第一首开始", "");
        PoemLibrary.Playback pseudo = new PoemLibrary.Playback(seq, PoemLibrary.ORDER_PSEUDO, 42);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < seq.size(); i++) ids.add(pseudo.next().id);
        check(ids.size() == seq.size(), "伪随机一轮不重复", "unique=" + ids.size());

        PoemMotion.Compiled linear = PoemMotion.compile("p");
        PoemMotion.Vars vars = new PoemMotion.Vars();
        vars.p = 0.25; vars.duration = 10; vars.speed = 120; vars.width = 1080;
        vars.textWidth = 320; vars.padding = 8; vars.chars = 12;
        check(Math.abs(linear.eval(vars) - 0.25) < 1e-9, "线性位置表达式", "");
        PoemMotion.Compiled smooth = PoemMotion.compile("smoothstep(0,1,p)");
        float[] lut = PoemMotion.sample(smooth, 257, vars);
        check(lut.length == 257 && Math.abs(lut[128] - 0.5f) < 0.01f, "LUT 采样", "mid=" + lut[128]);
        check(Math.abs(PoemMotion.lookup(new float[]{0f, 1f}, 0.25) - 0.25f) < 1e-6, "LUT 插值", "");
        boolean badRejected = false;
        try { PoemMotion.validate("unknownVar + 1", true); }
        catch (Throwable t) { badRejected = true; }
        check(badRejected, "非法变量被拒绝", "");

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
