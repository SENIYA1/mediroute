package com.mediroute;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Dispatch d = new Dispatch();
        d.realloc(null);
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/api/", ex -> {
            try { send(ex, 200, api(d, ex), "application/json"); }
            catch (Exception e) { send(ex, 400, "{\"error\":" + Dispatch.q(String.valueOf(e.getMessage())) + "}", "application/json"); }
        });
        s.createContext("/", Main::staticFile);
        s.setExecutor(Executors.newFixedThreadPool(8));
        s.start();
        System.out.println("MediRoute running at http://localhost:" + port);
    }

    static String api(Dispatch d, HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod(), p = ex.getRequestURI().getPath().substring(5);
        String[] seg = p.split("/");
        Map<String, String> f = form(ex);
        if (seg[0].equals("state")) return d.state();
        if (seg[0].equals("reset") && m.equals("POST")) return d.reset();
        if (seg[0].equals("emergencies")) {
            if (m.equals("POST")) return d.addEmergency(f);
            if (m.equals("DELETE") && seg.length > 1) return d.resolve(seg[1]);
        }
        if (seg[0].equals("ambulances") && seg.length > 2 && seg[2].equals("toggle")) return d.toggleAmbulance(seg[1]);
        if (seg[0].equals("hospitals") && seg.length > 2) {
            if (seg[2].equals("toggle")) return d.toggleHospital(seg[1]);
            if (seg[2].equals("beds")) return d.setBeds(seg[1], Integer.parseInt(f.getOrDefault("n", "0")));
        }
        throw new IllegalArgumentException("Unknown request");
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(dec(kv.substring(0, i)), dec(kv.substring(i + 1)));
        }
        return m;
    }

    static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        InputStream in = path.contains("..") ? null : Main.class.getResourceAsStream("/static" + path);
        if (in == null) { send(ex, 404, "Not found", "text/plain"); return; }
        String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "text/javascript" : "application/octet-stream";
        send(ex, 200, new String(readAll(in), StandardCharsets.UTF_8), type);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; }
    }

    static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type.startsWith("application/json") ? "application/json; charset=utf-8" : type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
}
