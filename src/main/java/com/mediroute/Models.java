package com.mediroute;

import java.util.*;

public class Models {
    public enum Priority { MINOR, MODERATE, SERIOUS, CRITICAL }
    public enum Kind { GENERAL, CARDIAC, TRAUMA, STROKE, RESPIRATORY }
    public enum Level { BLS, ALS, ICU }

    public static class Ambulance {
        public String id; public Level level; public double lat, lon; public boolean available = true;
        public Ambulance(String id, Level level, double lat, double lon) { this.id = id; this.level = level; this.lat = lat; this.lon = lon; }
    }
    public static class Hospital {
        public String id, name; public double lat, lon; public int beds, capacity; public boolean open = true; public Set<Kind> services;
        public Hospital(String id, String name, double lat, double lon, int beds, Set<Kind> services) {
            this.id = id; this.name = name; this.lat = lat; this.lon = lon; this.beds = beds; this.capacity = beds; this.services = services;
        }
    }
    public static class Emergency {
        public String id, patient, owner; public Kind kind; public Priority priority; public double lat, lon; public long arrived;
        public Emergency(String id, String patient, Kind kind, Priority priority, double lat, double lon, long arrived, String owner) {
            this.id = id; this.patient = patient; this.kind = kind; this.priority = priority; this.lat = lat; this.lon = lon; this.arrived = arrived; this.owner = owner;
        }
    }
    public static class Assignment {
        public String emergencyId, ambulanceId, hospitalId; public double pickupKm, hospitalKm;
    }
}
