package io.github.wumisc.antiburn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Structured poem library, defaults, JSON and playback ordering. */
public final class PoemLibrary {
    public static final String ORDER_SEQUENTIAL = "sequential";
    public static final String ORDER_RANDOM = "random";
    public static final String ORDER_PSEUDO = "pseudo";
    public static final String ORDER_AUTHOR = "author";

    private static final String[][] BUILTIN = {
            {"长风破浪会有时，直挂云帆济沧海。", "李白", "行路难"},
            {"欲穷千里目，更上一层楼。", "王之涣", "登鹳雀楼"},
            {"会当凌绝顶，一览众山小。", "杜甫", "望岳"},
            {"黑发不知勤学早，白首方悔读书迟。", "颜真卿", "劝学"},
            {"沉舟侧畔千帆过，病树前头万木春。", "刘禹锡", "酬乐天扬州初逢席上见赠"},
            {"千磨万击还坚劲，任尔东西南北风。", "郑燮", "竹石"}
    };

    private PoemLibrary() {}

    public static List<PoemItem> defaults() {
        List<PoemItem> out = new ArrayList<>();
        for (String[] row : BUILTIN) {
            out.add(new PoemItem("", row[0], row[1], row[2], PoemItem.ORIGIN_LOCAL, true));
        }
        return out;
    }

    public static String defaultsJson() {
        return PoemItem.listToJson(defaults());
    }

    public static String ensureLocal(String json) {
        return json == null || json.trim().isEmpty() ? defaultsJson() : json;
    }

    public static List<PoemItem> local(String json) {
        return PoemItem.listFromJson(ensureLocal(json), PoemItem.ORIGIN_LOCAL);
    }

    public static List<PoemItem> online(String json) {
        return PoemItem.listFromJson(json, PoemItem.ORIGIN_ONLINE);
    }

    public static List<PoemItem> mergeEnabled(String localJson, String onlineJson) {
        List<PoemItem> out = new ArrayList<>();
        for (PoemItem p : local(localJson)) {
            if (p.enabled) out.add(p);
        }
        for (PoemItem p : online(onlineJson)) {
            if (p.enabled) out.add(p);
        }
        return out;
    }

    public static String migrateLegacy(String customPoems) {
        List<PoemItem> out = new ArrayList<>(defaults());
        Set<String> seen = new HashSet<>();
        for (PoemItem p : out) seen.add(p.id);
        if (customPoems != null) {
            for (String line : customPoems.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                String text = parts.length > 0 ? parts[0].trim() : "";
                if (text.isEmpty()) continue;
                PoemItem p = new PoemItem("", text,
                        parts.length > 1 ? parts[1].trim() : "",
                        parts.length > 2 ? parts[2].trim() : "",
                        PoemItem.ORIGIN_LOCAL, true);
                if (seen.add(p.id)) out.add(p);
            }
        }
        return PoemItem.listToJson(out);
    }

    public static boolean duplicate(List<PoemItem> list, String text, String author, String source, String exceptId) {
        String id = PoemItem.stableId(text, author, source);
        for (PoemItem p : list) {
            if (id.equals(p.id) && !id.equals(exceptId)) return true;
        }
        return false;
    }

    /** Stateful playback cursor. */
    public static final class Playback {
        private final List<PoemItem> source;
        private final String mode;
        private final Random random;
        private final List<PoemItem> bag = new ArrayList<>();
        private int cursor = -1;
        private int lastIndex = -1;

        public Playback(List<PoemItem> source, String mode, long seed) {
            this.source = source == null ? Collections.<PoemItem>emptyList() : source;
            this.mode = normMode(mode);
            this.random = new Random(seed);
            if (ORDER_PSEUDO.equals(this.mode)) refillBag();
        }

        public PoemItem next() {
            if (source.isEmpty()) return null;
            switch (mode) {
                case ORDER_RANDOM:
                    return source.get(random.nextInt(source.size()));
                case ORDER_PSEUDO: {
                    if (bag.isEmpty()) refillBag();
                    return bag.remove(bag.size() - 1);
                }
                case ORDER_AUTHOR:
                    return nextAuthorAware();
                case ORDER_SEQUENTIAL:
                default:
                    cursor = (cursor + 1) % source.size();
                    lastIndex = cursor;
                    return source.get(cursor);
            }
        }

        private PoemItem nextAuthorAware() {
            if (lastIndex >= 0 && lastIndex < source.size() && random.nextDouble() < 0.5) {
                String author = source.get(lastIndex).author;
                if (!author.isEmpty()) {
                    List<Integer> same = new ArrayList<>();
                    for (int i = 0; i < source.size(); i++) {
                        if (i != lastIndex && author.equals(source.get(i).author)) same.add(i);
                    }
                    if (!same.isEmpty()) {
                        lastIndex = same.get(random.nextInt(same.size()));
                        return source.get(lastIndex);
                    }
                }
            }
            int next;
            if (source.size() == 1) {
                next = 0;
            } else {
                do {
                    next = random.nextInt(source.size());
                } while (next == lastIndex);
            }
            lastIndex = next;
            return source.get(next);
        }

        private void refillBag() {
            bag.clear();
            bag.addAll(source);
            Collections.shuffle(bag, random);
            if (bag.size() > 1 && lastIndex >= 0 && bag.get(bag.size() - 1) == source.get(lastIndex)) {
                Collections.swap(bag, bag.size() - 1, 0);
            }
        }
    }

    public static String normMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (ORDER_RANDOM.equals(m) || ORDER_PSEUDO.equals(m) || ORDER_AUTHOR.equals(m)) return m;
        return ORDER_SEQUENTIAL;
    }
}
