package com.mediroute;

import com.mediroute.Models.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

/** Fetches real hospitals around a point from OpenStreetMap (Overpass API). */
public class Overpass {
    static String[] urls() {
        String custom = System.getenv("OVERPASS_URL");
        if (custom != null && !custom.isEmpty()) return new String[]{custom};
        return new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"};
    }

    public static List<Hospital> fetch(double lat, double lon, int radiusM, int max) {
        String around = "(around:" + radiusM + "," + lat + "," + lon + ")";
        String q = "[out:json][timeout:20];(node[\"amenity\"=\"hospital\"]" + around + ";way[\"amenity\"=\"hospital\"]" + around
                + ";relation[\"amenity\"=\"hospital\"]" + around + ";);out center tags;";
        for (String url : urls()) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(12000); c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("User-Agent", "MediRoute/1.0 (student project)");
                try (OutputStream o = c.getOutputStream()) { o.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8")); }
                if (c.getResponseCode() != 200) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                try (InputStream in = c.getInputStream()) { while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 5000000) break; } }
                return parse(new String(bo.toByteArray(), "UTF-8"), lat, lon, max);
            } catch (Exception e) { System.err.println("Overpass " + url + " failed: " + e); }
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    static List<Hospital> parse(String body, double lat, double lon, int max) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(body);
        List<Object> els = (List<Object>) root.get("elements");
        List<Hospital> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (els == null) return out;
        for (Object o : els) {
            Map<String, Object> el = (Map<String, Object>) o;
            Map<String, Object> tags = el.get("tags") instanceof Map ? (Map<String, Object>) el.get("tags") : new HashMap<String, Object>();
            Object la = el.get("lat"), lo = el.get("lon");
            if (la == null && el.get("center") instanceof Map) { Map<String, Object> c = (Map<String, Object>) el.get("center"); la = c.get("lat"); lo = c.get("lon"); }
            String name = Auth.clean(String.valueOf(tags.get("name") != null ? tags.get("name") : tags.get("name:en") != null ? tags.get("name:en") : ""), 80).replace("|", " ");
            if (name.isEmpty() || la == null || lo == null || "no".equals(tags.get("emergency")) || !seen.add(name.toLowerCase())) continue;
            out.add(new Hospital("", name, ((Number) la).doubleValue(), ((Number) lo).doubleValue(), 10, EnumSet.allOf(Kind.class)));
        }
        out.sort(Comparator.comparingDouble(h -> Allocator.dist(lat, lon, h.lat, h.lon)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
