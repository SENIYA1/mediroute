package com.mediroute;

import com.mediroute.Auth.Role;
import com.mediroute.Auth.User;
import com.mediroute.Models.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.mediroute.Json.map;

/** Live dispatch state. Recomputes the plan on every event and builds role-specific views. */
public class Dispatch {
    static final double KM_PER_MIN = 0.6;
    static final double[][] FLEET_GRID = {{6, 8}, {11, 6}, {16, 8}, {3, 12}, {19, 13}};
    double cLat, cLon;
    final List<Ambulance> ambs = new ArrayList<>();
    final List<Hospital> hosps = new ArrayList<>();
    final List<Emergency> ems = new ArrayList<>();
    final LinkedList<String[]> log = new LinkedList<>();
    Allocator.Plan plan = new Allocator.Plan();
    int seq = 0, hseq = 3;
    final Map<String, Long> fetched = new HashMap<>();
    long lastFetch = 0;

    public Dispatch() { seed(); }

    static boolean staff(User u) { return u.role == Role.ADMIN || u.role == Role.DISPATCHER; }
    static HttpError forbidden() { return new HttpError(403, "Your role cannot do that"); }

    /** Demo grid (km offsets) converted to lat/lon around the current centre. */
    double[] pos(double x, double y) {
        return new double[]{cLat - (y - 8) / 111.0, cLon + (x - 12) / (111.0 * Math.cos(Math.toRadians(cLat)))};
    }

    synchronized void seed() {
        cLat = 13.0358; cLon = 80.1567; hseq = 3; fetched.clear();
        ambs.clear(); hosps.clear(); ems.clear(); log.clear(); plan = new Allocator.Plan(); seq = 0;
        Level[] lv = {Level.ALS, Level.ICU, Level.ALS, Level.BLS, Level.BLS};
        for (int i = 0; i < 5; i++) { double[] p = pos(FLEET_GRID[i][0], FLEET_GRID[i][1]); ambs.add(new Ambulance("A" + (i + 1), lv[i], p[0], p[1])); }
        double[] h1 = pos(9, 7), h2 = pos(18, 10), h3 = pos(5, 13);
        hosps.add(new Hospital("H1", "Central General (demo)", h1[0], h1[1], 4, EnumSet.allOf(Kind.class)));
        hosps.add(new Hospital("H2", "Riverside Medical (demo)", h2[0], h2[1], 2, EnumSet.of(Kind.GENERAL, Kind.TRAUMA, Kind.RESPIRATORY)));
        hosps.add(new Hospital("H3", "Lakeview Heart and Stroke (demo)", h3[0], h3[1], 2, EnumSet.of(Kind.GENERAL, Kind.CARDIAC, Kind.STROKE)));
        double[] c1 = pos(6, 6), c2 = pos(16, 11), c3 = pos(12, 13);
        add("Meena R.", Kind.CARDIAC, Priority.CRITICAL, c1[0], c1[1], null);
        add("Arjun S.", Kind.TRAUMA, Priority.MODERATE, c2[0], c2[1], null);
        add("Kavitha M.", Kind.STROKE, Priority.CRITICAL, c3[0], c3[1], null);
        log("Shift started");
        realloc(null);
    }

    void log(String msg) {
        log.addFirst(new String[]{LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), msg});
        while (log.size() > 40) log.removeLast();
    }

    Emergency add(String patient, Kind k, Priority p, double lat, double lon, String owner) {
        Emergency e = new Emergency("P" + (++seq), patient, k, p, lat, lon, System.nanoTime(), owner);
        ems.add(e); return e;
    }

    void realloc(String why) {
        Map<String, String> old = new HashMap<>();
        for (Assignment a : plan.assigned) old.put(a.emergencyId, a.ambulanceId);
        plan = Allocator.allocate(ambs, hosps, ems);
        if (why != null) log(why);
        for (Assignment a : plan.assigned) {
            String o = old.get(a.emergencyId);
            if (o == null) log(a.emergencyId + ": " + a.ambulanceId + " dispatched");
            else if (!o.equals(a.ambulanceId)) log(a.emergencyId + ": " + a.ambulanceId + " replaces " + o);
        }
        for (Map.Entry<String, String> u : plan.unassigned.entrySet())
            if (old.containsKey(u.getKey())) log(u.getKey() + " has no ambulance now. " + u.getValue());
    }

    static double num(Map<String, String> f, String key, double min, double max) {
        double v;
        try { v = Double.parseDouble(f.getOrDefault(key, "")); } catch (NumberFormatException e) { throw new HttpError(400, "Invalid location"); }
        if (Double.isNaN(v) || v < min || v > max) throw new HttpError(400, "Invalid location");
        return v;
    }
    Ambulance amb(String id) { for (Ambulance a : ambs) if (a.id.equals(id)) return a; return null; }
    Hospital hosp(String id) { for (Hospital h : hosps) if (h.id.equals(id)) return h; return null; }
    Assignment assignmentOf(String emergencyId) {
        for (Assignment a : plan.assigned) if (a.emergencyId.equals(emergencyId)) return a;
        return null;
    }


    // ---- automatic nearby hospitals ----


    /** Parks an ambulance at a real hospital (so it always sits on land, like a real ambulance base). */
    void station(Ambulance a, int i) {
        if (hosps.isEmpty()) return;
        Hospital h = hosps.get(i % hosps.size());
        int stack = i / hosps.size();
        a.lat = h.lat + 0.0004 * stack; a.lon = h.lon + 0.0004 * stack;
    }

    boolean realHospitalNear(double lat, double lon, double km) {
        for (Hospital h : hosps) if (!h.name.endsWith("(demo)") && Allocator.dist(lat, lon, h.lat, h.lon) <= km) return true;
        return false;
    }

    /** Called before a call is created. If no real hospital is within 15 km, loads the nearest ones from OpenStreetMap. */
    public void ensureHospitalsNear(double lat, double lon) {
        String cell = Math.round(lat * 10) + "," + Math.round(lon * 10);
        synchronized (this) {
            if (realHospitalNear(lat, lon, 15)) return;
            long now = System.currentTimeMillis();
            Long t = fetched.get(cell);
            if ((t != null && now - t < 600000) || now - lastFetch < 5000) return;
            fetched.put(cell, now); lastFetch = now;
        }
        List<Hospital> found = Overpass.fetch(lat, lon, 15000, 25);   // network call, done outside the lock
        synchronized (this) {
            if (found.isEmpty()) { fetched.remove(cell); return; }
            boolean demo = hosps.removeIf(h -> h.name.endsWith("(demo)"));
            Set<String> names = new HashSet<>();
            for (Hospital h : hosps) names.add(h.name.toLowerCase());
            int added = 0;
            for (Hospital h : found) {
                if (hosps.size() >= 80 || !names.add(h.name.toLowerCase())) continue;
                h.id = "H" + (++hseq); hosps.add(h); added++;
            }
            if (demo) {   // first real area: move the demo fleet and drop demo calls that are far away
                cLat = lat; cLon = lon;
                ems.removeIf(e -> Allocator.dist(e.lat, e.lon, lat, lon) > 50);
                for (int i = 0; i < ambs.size() && i < FLEET_GRID.length; i++) {
                    Ambulance a = ambs.get(i);
                    if (Allocator.dist(a.lat, a.lon, lat, lon) > 50) station(a, i);
                }
            }
            realloc("Added " + added + " nearby hospitals from OpenStreetMap");
        }
    }

    // ---- actions (each one checks the caller's role) ----

    public synchronized void addEmergency(User u, Map<String, String> f) {
        double lat = num(f, "lat", -90, 90), lon = num(f, "lon", -180, 180);
        Kind k = Kind.valueOf(f.getOrDefault("kind", "GENERAL"));
        String name = Auth.clean(f.get("patient"), 40);
        Priority p; String owner = null;
        if (u.role == Role.PUBLIC) {
            int open = 0;
            for (Emergency e : ems) if (u.username.equals(e.owner)) open++;
            if (open >= 3) throw new HttpError(429, "You already have 3 open requests");
            p = "yes".equals(f.get("critical")) ? Priority.CRITICAL : Priority.MODERATE;
            owner = u.username; if (name.isEmpty()) name = u.display;
        } else if (staff(u)) {
            p = Priority.valueOf(f.getOrDefault("priority", "MODERATE"));
            if (name.isEmpty()) name = "Unknown patient";
        } else throw forbidden();
        Emergency e = add(name, k, p, lat, lon, owner);
        realloc("New call " + e.id + " for " + name);
    }

    public synchronized void resolve(User u, String id) {
        Emergency e = null;
        for (Emergency x : ems) if (x.id.equals(id)) e = x;
        if (e == null) throw new HttpError(404, "Call not found");
        Assignment a = assignmentOf(id);
        boolean ok = staff(u)
                || (u.role == Role.DRIVER && a != null && a.ambulanceId.equals(u.link))
                || (u.role == Role.PUBLIC && u.username.equals(e.owner));
        if (!ok) throw forbidden();
        ems.remove(e);
        realloc(id + (u.role == Role.PUBLIC ? " cancelled by caller" : " completed"));
    }


    public synchronized void addAmbulance(User u, String level) {
        if (u.role != Role.ADMIN) throw forbidden();
        if (ambs.size() >= 30) throw new HttpError(400, "Fleet limit reached (30 ambulances)");
        Level lv = Level.valueOf(level);
        int max = 0;
        for (Ambulance x : ambs) { try { max = Math.max(max, Integer.parseInt(x.id.substring(1))); } catch (NumberFormatException ignored) { } }
        Ambulance a = new Ambulance("A" + (max + 1), lv, cLat, cLon);
        ambs.add(a); station(a, ambs.size() - 1);
        realloc(a.id + " (" + lv + ") added to the fleet");
    }

    public synchronized void removeAmbulance(User u, String id) {
        if (u.role != Role.ADMIN) throw forbidden();
        Ambulance a = amb(id);
        if (a == null) throw new HttpError(404, "Ambulance not found");
        ambs.remove(a);
        realloc(id + " removed from the fleet");
    }

    public synchronized void toggleAmbulance(User u, String id) {
        Ambulance a = amb(id);
        if (a == null) throw new HttpError(404, "Ambulance not found");
        if (!(staff(u) || (u.role == Role.DRIVER && id.equals(u.link)))) throw forbidden();
        a.available = !a.available;
        realloc(id + (a.available ? " back in service" : " out of service"));
    }

    /** GPS update from a crew. Moves the marker and live ETA; the plan itself changes only on events. */
    public synchronized void location(User u, String id, Map<String, String> f) {
        Ambulance a = amb(id);
        if (a == null) throw new HttpError(404, "Ambulance not found");
        if (!(staff(u) || (u.role == Role.DRIVER && id.equals(u.link)))) throw forbidden();
        a.lat = num(f, "lat", -90, 90); a.lon = num(f, "lon", -180, 180);
    }

    public synchronized void toggleHospital(User u, String id) {
        Hospital h = hosp(id);
        if (h == null) throw new HttpError(404, "Hospital not found");
        if (!(staff(u) || (u.role == Role.HOSPITAL && id.equals(u.link)))) throw forbidden();
        h.open = !h.open;
        realloc(h.name + (h.open ? " accepting patients" : " closed to arrivals"));
    }

    public synchronized void setBeds(User u, String id, int n) {
        Hospital h = hosp(id);
        if (h == null) throw new HttpError(404, "Hospital not found");
        if (!(staff(u) || (u.role == Role.HOSPITAL && id.equals(u.link)))) throw forbidden();
        h.beds = Math.max(0, Math.min(h.capacity, n));
        realloc(h.name + " beds set to " + h.beds);
    }

    /** Admin: load a real area. Hospitals come from OpenStreetMap (fetched by the browser). */
    public synchronized void setRegion(User u, double lat, double lon, String text) {
        if (u.role != Role.ADMIN) throw forbidden();
        List<Hospital> hs = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            try {
                double la = Double.parseDouble(p[1]), lo = Double.parseDouble(p[2]);
                String name = Auth.clean(p[0], 80);
                if (name.isEmpty() || Math.abs(la) > 90 || Math.abs(lo) > 180) continue;
                hs.add(new Hospital("H" + (hs.size() + 1), name, la, lo, 10, EnumSet.allOf(Kind.class)));
            } catch (NumberFormatException ignored) { }
            if (hs.size() >= 40) break;
        }
        if (hs.isEmpty()) throw new HttpError(400, "No hospitals supplied");
        cLat = lat; cLon = lon;
        hosps.clear(); hosps.addAll(hs); ems.clear(); seq = 0;
        for (int i = 0; i < ambs.size(); i++) {
            station(ambs.get(i), i); ambs.get(i).available = true;
        }
        hseq = hs.size();
        realloc("Area loaded with " + hs.size() + " hospitals");
    }

    public synchronized void reset(User u) {
        if (u.role != Role.ADMIN) throw forbidden();
        seed();
    }

    // ---- role-filtered view ----

    public synchronized String state(User u) {
        Map<String, Assignment> byE = new HashMap<>(), byA = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (Assignment a : plan.assigned) { byE.put(a.emergencyId, a); byA.put(a.ambulanceId, a); incoming.merge(a.hospitalId, 1, Integer::sum); }
        boolean st = staff(u);

        List<Emergency> sorted = new ArrayList<>(ems);
        sorted.sort(Comparator.comparing((Emergency e) -> e.priority).reversed().thenComparingLong(e -> e.arrived));
        List<Object> eo = new ArrayList<>(); Set<String> shownAmb = new HashSet<>();
        for (Emergency e : sorted) {
            Assignment as = byE.get(e.id);
            boolean vis = st
                    || (u.role == Role.DRIVER && as != null && as.ambulanceId.equals(u.link))
                    || (u.role == Role.HOSPITAL && as != null && as.hospitalId.equals(u.link))
                    || (u.role == Role.PUBLIC && u.username.equals(e.owner));
            if (!vis) continue;
            Map<String, Object> m = map("id", e.id, "patient", e.patient, "kind", e.kind, "priority", e.priority,
                    "lat", e.lat, "lon", e.lon, "needs", Allocator.required(e));
            if (as != null) {
                Ambulance a = amb(as.ambulanceId);
                double pk = Allocator.dist(a.lat, a.lon, e.lat, e.lon);
                m.put("assignment", map("ambulanceId", as.ambulanceId, "hospitalId", as.hospitalId,
                        "pickupKm", pk, "pickupMin", pk / KM_PER_MIN, "hospitalKm", as.hospitalKm, "hospitalMin", as.hospitalKm / KM_PER_MIN));
                shownAmb.add(as.ambulanceId);
            } else {
                m.put("assignment", null); m.put("reason", plan.unassigned.getOrDefault(e.id, "Waiting"));
            }
            eo.add(m);
        }
        List<Object> ao = new ArrayList<>();
        for (Ambulance a : ambs) {
            if (!(st || (u.role == Role.DRIVER && a.id.equals(u.link)) || shownAmb.contains(a.id))) continue;
            Assignment as = byA.get(a.id);
            ao.add(map("id", a.id, "level", a.level, "lat", a.lat, "lon", a.lon, "available", a.available,
                    "busyWith", as == null ? null : as.emergencyId));
        }
        List<Object> ho = new ArrayList<>();
        for (Hospital h : hosps) {
            Map<String, Object> m = map("id", h.id, "name", h.name, "lat", h.lat, "lon", h.lon, "open", h.open);
            if (st || (u.role == Role.HOSPITAL && h.id.equals(u.link))) { m.put("beds", h.beds); m.put("capacity", h.capacity); m.put("incoming", incoming.getOrDefault(h.id, 0)); }
            ho.add(m);
        }
        List<Object> lo = new ArrayList<>();
        if (st) for (String[] l : log) lo.add(map("t", l[0], "msg", l[1]));
        return Json.write(map("me", map("username", u.username, "display", u.display, "role", u.role, "link", u.link),
                "center", map("lat", cLat, "lon", cLon), "ambulances", ao, "hospitals", ho, "emergencies", eo, "log", lo));
    }
}
