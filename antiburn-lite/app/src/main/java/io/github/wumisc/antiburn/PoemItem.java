package io.github.wumisc.antiburn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Immutable poem record shared by the app UI and the SystemUI hook. */
public final class PoemItem {
    public static final String ORIGIN_LOCAL = "local";
    public static final String ORIGIN_ONLINE = "online";

    public final String id;
    public final String text;
    public final String author;
    public final String source;
    public final String origin;
    public final boolean enabled;
    public final int charCount;

    public PoemItem(String id, String text, String author, String source,
                    String origin, boolean enabled) {
        this.id = id == null || id.isEmpty() ? stableId(text, author, source) : id;
        this.text = text == null ? "" : text.trim();
        this.author = author == null ? "" : author.trim();
        this.source = source == null ? "" : source.trim();
        this.origin = ORIGIN_ONLINE.equals(origin) ? ORIGIN_ONLINE : ORIGIN_LOCAL;
        this.enabled = enabled;
        this.charCount = this.text.codePointCount(0, this.text.length());
    }

    public PoemItem withEnabled(boolean value) {
        return new PoemItem(id, text, author, source, origin, value);
    }

    public String displayText(boolean showAuthor, boolean showSource) {
        StringBuilder out = new StringBuilder(text);
        if (showAuthor && !author.isEmpty()) {
            out.append(" —— ").append(author);
        }
        if (showSource && !source.isEmpty()) {
            out.append("《").append(source).append('》');
        }
        return out.toString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("text", text);
        o.put("author", author);
        o.put("source", source);
        o.put("origin", origin);
        o.put("enabled", enabled);
        o.put("chars", charCount);
        return o;
    }

    public static PoemItem fromJson(JSONObject o, String defaultOrigin) {
        String origin = o.optString("origin", defaultOrigin);
        return new PoemItem(
                o.optString("id", ""),
                o.optString("text", ""),
                o.optString("author", ""),
                o.optString("source", ""),
                origin,
                o.optBoolean("enabled", true));
    }

    public static List<PoemItem> listFromJson(String json, String defaultOrigin) {
        List<PoemItem> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                PoemItem p = fromJson(o, defaultOrigin);
                if (!p.text.isEmpty()) {
                    out.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String listToJson(List<PoemItem> items) {
        JSONArray arr = new JSONArray();
        if (items != null) {
            for (PoemItem p : items) {
                if (p != null && !p.text.isEmpty()) {
                    arr.put(p.toJson());
                }
            }
        }
        return arr.toString();
    }

    public static String stableId(String text, String author, String source) {
        String raw = (text == null ? "" : text.trim()) + "\u0000"
                + (author == null ? "" : author.trim()) + "\u0000"
                + (source == null ? "" : source.trim());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                out.append(String.format("%02x", digest[i] & 0xff));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
