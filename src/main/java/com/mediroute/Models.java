package com.mediroute;

import java.util.*;

public class Models {
    public enum Priority { MINOR, MODERATE, SERIOUS, CRITICAL }
    public enum Kind { GENERAL, CARDIAC, TRAUMA, STROKE, RESPIRATORY }
    public enum Level { BLS, ALS, ICU }

    public static class Ambulance {
        public String id; public Level level; public double x, y; public boolean available = true;
        public Ambulance(String id, Level level, double x, double y) { this.id = id; this.level = level; this.x = x; this.y = y; }
    }
    public static class Hospital {
        public String id, name; public double x, y; public int beds; public boolean open = true; public Set<Kind> services;
        public Hospital(String id, String name, double x, double y, int beds, Set<Kind> services) {
            this.id = id; this.name = name; this.x = x; this.y = y; this.beds = beds; this.services = services;
        }
    }
    public static class Emergency {
        public String id, patient; public Kind kind; public Priority priority; public double x, y; public long arrived;
        public Emergency(String id, String patient, Kind kind, Priority priority, double x, double y, long arrived) {
            this.id = id; this.patient = patient; this.kind = kind; this.priority = priority; this.x = x; this.y = y; this.arrived = arrived;
        }
    }
    public static class Assignment {
        public String emergencyId, ambulanceId, hospitalId; public double pickupKm, hospitalKm;
    }
}
