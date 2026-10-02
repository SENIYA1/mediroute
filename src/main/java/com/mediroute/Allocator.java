package com.mediroute;

import com.mediroute.Models.*;
import java.util.*;

public class Allocator {
    public static class Plan {
        public final List<Assignment> assigned = new ArrayList<>();
        public final Map<String, String> unassigned = new LinkedHashMap<>();
    }
    static final double BIG = 1e5;

    /** Great-circle (straight-line) distance in km. */
    static double dist(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2), dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371.0 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Minimum ambulance equipment level a call needs. */
    public static Level required(Emergency e) {
        if (e.priority == Priority.CRITICAL) return Level.ALS;
        if (e.priority == Priority.SERIOUS && e.kind != Kind.GENERAL) return Level.ALS;
        return Level.BLS;
    }

    public static Plan allocate(List<Ambulance> ambs, List<Hospital> hosps, List<Emergency> ems) {
        Plan plan = new Plan();
        Map<String, Integer> beds = new HashMap<>();
        for (Hospital h : hosps) if (h.open) beds.put(h.id, h.beds);
        List<Ambulance> pool = new ArrayList<>();
        for (Ambulance a : ambs) if (a.available) pool.add(a);

        for (int pi = Priority.values().length - 1; pi >= 0; pi--) {
            Priority p = Priority.values()[pi];
            List<Emergency> tier = new ArrayList<>();
            for (Emergency e : ems) if (e.priority == p) tier.add(e);
            tier.sort(Comparator.comparingLong(e -> e.arrived));

            // 1. destination hospital: open, offers the service, has a free bed, nearest to patient
            List<Emergency> cand = new ArrayList<>();
            Map<String, Hospital> pick = new HashMap<>();
            for (Emergency e : tier) {
                Hospital best = null; double bd = Double.MAX_VALUE;
                for (Hospital h : hosps) {
                    if (!h.open || beds.getOrDefault(h.id, 0) <= 0 || !h.services.contains(e.kind)) continue;
                    double d = dist(e.lat, e.lon, h.lat, h.lon);
                    if (d < bd) { bd = d; best = h; }
                }
                if (best == null) plan.unassigned.put(e.id, "No open hospital with a free bed for " + e.kind.name().toLowerCase() + " cases");
                else { beds.merge(best.id, -1, Integer::sum); pick.put(e.id, best); cand.add(e); }
            }
            if (cand.isEmpty()) continue;

            // 2. optimal ambulance matching inside the tier
            int n = cand.size(), m = pool.size(), k = Math.max(n, m);
            double[][] c = new double[k][k];
            for (int i = 0; i < n; i++) {
                Emergency e = cand.get(i); Hospital h = pick.get(e.id);
                double hd = dist(e.lat, e.lon, h.lat, h.lon); int need = required(e).ordinal();
                for (int j = 0; j < k; j++) {
                    if (j >= m) { c[i][j] = BIG; continue; }
                    Ambulance a = pool.get(j);
                    c[i][j] = a.level.ordinal() < need ? BIG
                            : dist(a.lat, a.lon, e.lat, e.lon) + 0.25 * hd + 0.5 * (a.level.ordinal() - need);
                }
            }
            int[] match = hungarian(c);
            Set<Ambulance> used = new HashSet<>();
            for (int i = 0; i < n; i++) {
                Emergency e = cand.get(i); Hospital h = pick.get(e.id); int j = match[i];
                if (j < m && c[i][j] < BIG) {
                    Ambulance a = pool.get(j); used.add(a);
                    Assignment as = new Assignment();
                    as.emergencyId = e.id; as.ambulanceId = a.id; as.hospitalId = h.id;
                    as.pickupKm = dist(a.lat, a.lon, e.lat, e.lon); as.hospitalKm = dist(e.lat, e.lon, h.lat, h.lon);
                    plan.assigned.add(as);
                } else {
                    beds.merge(h.id, 1, Integer::sum);
                    plan.unassigned.put(e.id, m == 0 ? "No ambulance in service"
                            : "No free ambulance with " + required(e) + " equipment or better");
                }
            }
            pool.removeAll(used);
        }
        return plan;
    }

    /** Hungarian algorithm (square matrix). Returns the column chosen for each row. */
    static int[] hungarian(double[][] a) {
        int n = a.length;
        double[] u = new double[n + 1], v = new double[n + 1];
        int[] p = new int[n + 1], way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i; int j0 = 0;
            double[] minv = new double[n + 1]; Arrays.fill(minv, Double.MAX_VALUE);
            boolean[] used = new boolean[n + 1];
            do {
                used[j0] = true; int i0 = p[j0], j1 = 0; double delta = Double.MAX_VALUE;
                for (int j = 1; j <= n; j++) if (!used[j]) {
                    double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0; }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j; }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; } else minv[j] -= delta;
                }
                j0 = j1;
            } while (p[j0] != 0);
            do { int j1 = way[j0]; p[j0] = p[j1]; j0 = j1; } while (j0 != 0);
        }
        int[] res = new int[n];
        for (int j = 1; j <= n; j++) res[p[j] - 1] = j - 1;
        return res;
    }
}
