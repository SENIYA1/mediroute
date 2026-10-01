package com.mediroute;

import com.mediroute.Models.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Holds the live dispatch state and recomputes the plan after every change. */
public class Dispatch {
    static final double KM_PER_MIN = 0.6;
    final List<Ambulance> ambs = new ArrayList<>();
    final List<Hospital> hosps = new ArrayList<>();
    final List<Emergency> ems = new ArrayList<>();
    final LinkedList<String[]> log = new LinkedList<>();
    Allocator.Plan plan = new Allocator.Plan();
    int seq = 0;

    public Dispatch() { seed(); }

    synchronized void seed() {
        ambs.clear(); hosps.clear(); ems.clear(); log.clear(); plan = new Allocator.Plan(); seq = 0;
        ambs.add(new Ambulance("A1", Level.ALS, 6, 8));
        ambs.add(new Ambulance("A2", Level.ICU, 11, 6));
        ambs.add(new Ambulance("A3", Level.ALS, 16, 8));
        ambs.add(new Ambulance("A4", Level.BLS, 3, 12));
        ambs.add(new Ambulance("A5", Level.BLS, 19, 13));
        hosps.add(new Hospital("H1", "Central General", 9, 7, 4, EnumSet.allOf(Kind.class)));
        hosps.add(new Hospital("H2", "Riverside Medical", 18, 10, 2, EnumSet.of(Kind.GENERAL, Kind.TRAUMA, Kind.RESPIRATORY)));
        hosps.add(new Hospital("H3", "Lakeview Heart and Stroke", 5, 13, 2, EnumSet.of(Kind.GENERAL, Kind.CARDIAC, Kind.STROKE)));
        add("Meena R.", Kind.CARDIAC, Priority.CRITICAL, 6, 6);
        add("Arjun S.", Kind.TRAUMA, Priority.MODERATE, 16, 11);
        add("Kavitha M.", Kind.STROKE, Priority.CRITICAL, 12, 13);
        log("Shift started");
    }

    void log(String msg) {
        log.addFirst(new String[]{LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), msg});
        while (log.size() > 40) log.removeLast();
    }

    void add(String patient, Kind k, Priority p, double x, double y) {
        ems.add(new Emergency("P" + (++seq), patient, k, p, x, y, System.nanoTime()));
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

    public synchronized String addEmergency(Map<String, String> f) {
        String name = f.getOrDefault("patient", "").trim();
        if (name.isEmpty()) name = "Unknown patient";
        double x = Math.max(0, Math.min(24, Double.parseDouble(f.getOrDefault("x", "12"))));
        double y = Math.max(0, Math.min(16, Double.parseDouble(f.getOrDefault("y", "8"))));
        add(name, Kind.valueOf(f.getOrDefault("kind", "GENERAL")), Priority.valueOf(f.getOrDefault("priority", "MODERATE")), x, y);
        realloc("New call P" + seq + " for " + name);
        return state();
    }
    public synchronized String resolve(String id) {
        ems.removeIf(e -> e.id.equals(id)); realloc(id + " resolved"); return state();
    }
    public synchronized String toggleAmbulance(String id) {
        for (Ambulance a : ambs) if (a.id.equals(id)) { a.available = !a.available; realloc(id + (a.available ? " back in service" : " out of service")); }
        return state();
    }
    public synchronized String toggleHospital(String id) {
        for (Hospital h : hosps) if (h.id.equals(id)) { h.open = !h.open; realloc(h.name + (h.open ? " accepting patients" : " closed to arrivals")); }
        return state();
    }
    public synchronized String setBeds(String id, int n) {
        for (Hospital h : hosps) if (h.id.equals(id)) { h.beds = Math.max(0, Math.min(20, n)); realloc(h.name + " beds set to " + h.beds); }
        return state();
    }
    public synchronized String reset() { seed(); realloc(null); return state(); }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"') b.append("\\\""); else if (c == '\\') b.append("\\\\"); else if (c < 32) b.append(' '); else b.append(c);
        }
        return b.append('"').toString();
    }
    static String n(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    public synchronized String state() {
        Map<String, Assignment> byE = new HashMap<>(), byA = new HashMap<>();
        Map<String, Integer> used = new HashMap<>();
        for (Assignment a : plan.assigned) { byE.put(a.emergencyId, a); byA.put(a.ambulanceId, a); used.merge(a.hospitalId, 1, Integer::sum); }
        List<Emergency> sorted = new ArrayList<>(ems);
        sorted.sort(Comparator.comparing((Emergency e) -> e.priority).reversed().thenComparingLong(e -> e.arrived));
        StringBuilder b = new StringBuilder("{\"ambulances\":[");
        for (int i = 0; i < ambs.size(); i++) {
            Ambulance a = ambs.get(i); Assignment as = byA.get(a.id);
            b.append(i > 0 ? "," : "").append("{\"id\":").append(q(a.id)).append(",\"level\":").append(q(a.level.name()))
             .append(",\"x\":").append(n(a.x)).append(",\"y\":").append(n(a.y)).append(",\"available\":").append(a.available)
             .append(",\"busyWith\":").append(as == null ? "null" : q(as.emergencyId)).append("}");
        }
        b.append("],\"hospitals\":[");
        for (int i = 0; i < hosps.size(); i++) {
            Hospital h = hosps.get(i);
            b.append(i > 0 ? "," : "").append("{\"id\":").append(q(h.id)).append(",\"name\":").append(q(h.name))
             .append(",\"x\":").append(n(h.x)).append(",\"y\":").append(n(h.y)).append(",\"beds\":").append(h.beds)
             .append(",\"incoming\":").append(used.getOrDefault(h.id, 0)).append(",\"open\":").append(h.open).append("}");
        }
        b.append("],\"emergencies\":[");
        for (int i = 0; i < sorted.size(); i++) {
            Emergency e = sorted.get(i); Assignment as = byE.get(e.id);
            b.append(i > 0 ? "," : "").append("{\"id\":").append(q(e.id)).append(",\"patient\":").append(q(e.patient))
             .append(",\"kind\":").append(q(e.kind.name())).append(",\"priority\":").append(q(e.priority.name()))
             .append(",\"x\":").append(n(e.x)).append(",\"y\":").append(n(e.y)).append(",\"needs\":").append(q(Allocator.required(e).name()));
            if (as != null) b.append(",\"assignment\":{\"ambulanceId\":").append(q(as.ambulanceId)).append(",\"hospitalId\":").append(q(as.hospitalId))
                .append(",\"pickupKm\":").append(n(as.pickupKm)).append(",\"pickupMin\":").append(n(as.pickupKm / KM_PER_MIN))
                .append(",\"hospitalKm\":").append(n(as.hospitalKm)).append(",\"hospitalMin\":").append(n(as.hospitalKm / KM_PER_MIN)).append("}");
            else b.append(",\"assignment\":null,\"reason\":").append(q(plan.unassigned.getOrDefault(e.id, "Waiting")));
            b.append("}");
        }
        b.append("],\"log\":[");
        int i = 0;
        for (String[] l : log) b.append(i++ > 0 ? "," : "").append("{\"t\":").append(q(l[0])).append(",\"msg\":").append(q(l[1])).append("}");
        return b.append("]}").toString();
    }
}
