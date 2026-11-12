package io.github.wumisc.antiburn;

import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** App-side online poem refresh. SystemUI never performs network I/O. */
public final class PoemOnlineClient {
    private static final String URL = "https://v1.hitokoto.cn/?c=i&encode=json";

    private PoemOnlineClient() {}

    public static String refresh(Context context) {
        Map<String, Boolean> oldEnabled = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>();
        try {
            String conf = ConfStore.read();
            if (conf != null) {
                for (String line : conf.split("\n")) {
                    int i = line.indexOf('=');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if ("poem_online".equals(k)) {
                        for (PoemItem p : PoemItem.listFromJson(ConfStore.decodeValue(v), PoemItem.ORIGIN_ONLINE)) {
                            oldEnabled.put(p.id, p.enabled);
                        }
                    } else if ("poem_deleted_online".equals(k)) {
                        for (String id : v.split(",")) if (!id.trim().isEmpty()) deleted.add(id.trim());
                    }
                }
            }
        } catch (Throwable ignored) {}

        List<PoemItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int attempt = 0; attempt < 12 && out.size() < 6; attempt++) {
            PoemItem item = fetchOne();
            if (item == null || deleted.contains(item.id) || !seen.add(item.id)) continue;
            Boolean enabled = oldEnabled.get(item.id);
            out.add(new PoemItem(item.id, item.text, item.author, item.source,
                    PoemItem.ORIGIN_ONLINE, enabled == null || enabled));
        }
        return out.isEmpty() ? null : PoemItem.listToJson(out);
    }

    private static PoemItem fetchOne() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(URL).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            String text = o.optString("hitokoto", "").trim();
            if (text.isEmpty()) return null;
            return new PoemItem("", text, o.optString("from_who", ""), o.optString("from", ""),
                    PoemItem.ORIGIN_ONLINE, true);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(PoemOnlineWorker.class, 24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "antiburn-poems-daily", ExistingPeriodicWorkPolicy.KEEP, work);
        } catch (Throwable ignored) {}
    }

    public static final class PoemOnlineWorker extends Worker {
        public PoemOnlineWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull @Override
        public Result doWork() {
            String json = refresh(getApplicationContext());
            if (json == null) return Result.retry();
            Map<String, String> update = new LinkedHashMap<>();
            update.put("poem_online", ConfStore.encodeValue(json));
            return ConfStore.updateEx(update) == ConfStore.WRITE_FAILED ? Result.retry() : Result.success();
        }
    }
}
