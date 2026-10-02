package com.mediroute;

import com.mediroute.Auth.Role;
import com.mediroute.Auth.User;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

import static com.mediroute.Json.map;

public class Main {
    static final Auth auth = new Auth();
    static final Dispatch dispatch = new Dispatch();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String adminPw = System.getenv("ADMIN_PASSWORD");
        if (adminPw == null || adminPw.isEmpty()) {
            adminPw = "admin123";
            System.out.println("WARNING: using the default admin password. Set ADMIN_PASSWORD before sharing this server.");
        }
        auth.create("admin", "Administrator", adminPw, Role.ADMIN, null);
        auth.create("dispatcher1", "Dispatcher One", "dispatch123", Role.DISPATCHER, null);
        auth.create("driver1", "Crew A1", "driver123", Role.DRIVER, "A1");
        auth.create("driver2", "Crew A2", "driver123", Role.DRIVER, "A2");
        auth.create("hospital1", "Hospital Desk H1", "hospital123", Role.HOSPITAL, "H1");
        auth.create("citizen1", "Demo Citizen", "public123", Role.PUBLIC, null);

        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/api/", ex -> {
            try { send(ex, 200, handle(ex), "application/json"); }
            catch (HttpError e) { send(ex, e.code, Json.write(map("error", e.getMessage())), "application/json"); }
            catch (IllegalArgumentException e) { send(ex, 400, Json.write(map("error", "Invalid input")), "application/json"); }
            catch (Exception e) { e.printStackTrace(); send(ex, 500, Json.write(map("error", "Server error")), "application/json"); }
        });
        s.createContext("/", Main::staticFile);
        s.setExecutor(Executors.newFixedThreadPool(8));
        s.start();
        System.out.println("MediRoute running at http://localhost:" + port);
    }

    static String token(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String part : c.split(";")) { part = part.trim(); if (part.startsWith("MR=")) return part.substring(3); }
        return null;
    }

    static void setCookie(HttpExchange ex, String value, int maxAge) {
        ex.getResponseHeaders().add("Set-Cookie", "MR=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge);
    }

    static String handle(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod(), path = ex.getRequestURI().getPath().substring(5);
        String[] seg = path.split("/");
        Map<String, String> f = form(ex);
        if (!m.equals("GET") && !"1".equals(ex.getRequestHeaders().getFirst("X-MR"))) throw new HttpError(403, "Bad request origin");

        // --- no login needed ---
        if (seg[0].equals("login") && m.equals("POST")) {
            setCookie(ex, auth.login(f.get("username"), f.get("password")), 28800);
            return "{\"ok\":true}";
        }
        if (seg[0].equals("register") && m.equals("POST")) {
            auth.create(f.get("username"), f.get("display"), f.get("password"), Role.PUBLIC, null);
            setCookie(ex, auth.login(f.get("username"), f.get("password")), 28800);
            return "{\"ok\":true}";
        }
        if (seg[0].equals("logout") && m.equals("POST")) { auth.logout(token(ex)); setCookie(ex, "", 0); return "{\"ok\":true}"; }

        // --- everything below needs a signed-in user ---
        User u = auth.userFor(token(ex));
        if (u == null) throw new HttpError(401, "Please sign in");
        Dispatch d = dispatch;

        if (seg[0].equals("state") && m.equals("GET")) return d.state(u);
        if (seg[0].equals("emergencies")) {
            if (m.equals("POST")) {
                if (Dispatch.staff(u) || u.role == Role.PUBLIC)
                    d.ensureHospitalsNear(Dispatch.num(f, "lat", -90, 90), Dispatch.num(f, "lon", -180, 180));
                d.addEmergency(u, f); return d.state(u);
            }
            if (m.equals("DELETE") && seg.length > 1) { d.resolve(u, seg[1]); return d.state(u); }
        }
        if (seg[0].equals("ambulances") && seg.length == 1 && m.equals("POST")) { d.addAmbulance(u, f.getOrDefault("level", "BLS")); return d.state(u); }
        if (seg[0].equals("ambulances") && seg.length == 2 && m.equals("DELETE")) { d.removeAmbulance(u, seg[1]); return d.state(u); }
        if (seg[0].equals("ambulances") && seg.length > 2 && m.equals("POST")) {
            if (seg[2].equals("toggle")) { d.toggleAmbulance(u, seg[1]); return d.state(u); }
            if (seg[2].equals("location")) { d.location(u, seg[1], f); return d.state(u); }
        }
        if (seg[0].equals("hospitals") && seg.length > 2 && m.equals("POST")) {
            if (seg[2].equals("toggle")) { d.toggleHospital(u, seg[1]); return d.state(u); }
            if (seg[2].equals("beds")) { d.setBeds(u, seg[1], Integer.parseInt(f.getOrDefault("n", "0"))); return d.state(u); }
        }
        if (seg[0].equals("region") && m.equals("POST")) {
            d.setRegion(u, Double.parseDouble(f.getOrDefault("lat", "")), Double.parseDouble(f.getOrDefault("lon", "")), f.get("hospitals"));
            return d.state(u);
        }
        if (seg[0].equals("reset") && m.equals("POST")) { d.reset(u); return d.state(u); }

        if (seg[0].equals("users")) {
            if (u.role != Role.ADMIN) throw new HttpError(403, "Administrators only");
            if (m.equals("POST")) {
                Role r = Role.valueOf(f.getOrDefault("role", "PUBLIC"));
                String link = f.get("link");
                if (r == Role.DRIVER && (link == null || link.isEmpty())) throw new HttpError(400, "Pick an ambulance for this driver");
                if (r == Role.HOSPITAL && (link == null || link.isEmpty())) throw new HttpError(400, "Pick a hospital for this account");
                auth.create(f.get("username"), f.get("display"), f.get("password"), r, (r == Role.DRIVER || r == Role.HOSPITAL) ? link : null);
            } else if (m.equals("DELETE") && seg.length > 1) {
                if (seg[1].equalsIgnoreCase(u.username)) throw new HttpError(400, "You cannot delete your own account");
                auth.delete(seg[1]);
            }
            List<Object> out = new ArrayList<>();
            for (User x : auth.list()) out.add(map("username", x.username, "display", x.display, "role", x.role, "link", x.link));
            return Json.write(map("users", out));
        }
        throw new HttpError(404, "Unknown request");
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        String body = new String(readAll(ex.getRequestBody(), 100000), StandardCharsets.UTF_8);
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(dec(kv.substring(0, i)), dec(kv.substring(i + 1)));
        }
        return m;
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); if (o.size() > limit) throw new HttpError(413, "Request too large"); }
        return o.toByteArray();
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; }
    }

    static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        InputStream in = path.contains("..") ? null : Main.class.getResourceAsStream("/static" + path);
        if (in == null) { send(ex, 404, "Not found", "text/plain"); return; }
        String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "text/javascript" : "application/octet-stream";
        send(ex, 200, new String(readAll(in, 5000000), StandardCharsets.UTF_8), type);
    }

    static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type.startsWith("application/json") ? "application/json; charset=utf-8" : type);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
}
