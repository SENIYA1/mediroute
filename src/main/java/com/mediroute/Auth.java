package com.mediroute;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Users, password hashing (PBKDF2), sessions and login throttling. */
public class Auth {
    public enum Role { ADMIN, DISPATCHER, DRIVER, HOSPITAL, PUBLIC }

    public static class User {
        public final String username, display, link; public final Role role; final byte[] salt, hash;
        User(String username, String display, Role role, String link, byte[] salt, byte[] hash) {
            this.username = username; this.display = display; this.role = role; this.link = link; this.salt = salt; this.hash = hash;
        }
    }
    static class Session { final String user; final long expires; Session(String u, long e) { user = u; expires = e; } }

    static final long SESSION_MS = 8L * 3600 * 1000;
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, long[]> fails = new HashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    public static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private byte[] hash(String pw, byte[] salt) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 65536, 256)).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public User create(String username, String display, String pw, Role role, String link) {
        String u = username == null ? "" : username.trim();
        if (!u.matches("[A-Za-z0-9_.-]{3,24}")) throw new HttpError(400, "Username must be 3-24 characters: letters, digits, dot, dash or underscore");
        if (pw == null || pw.length() < 6 || pw.length() > 100) throw new HttpError(400, "Password must be at least 6 characters");
        String d = clean(display, 40); if (d.isEmpty()) d = u;
        byte[] salt = new byte[16]; rnd.nextBytes(salt);
        User nu = new User(u, d, role, (link == null || link.isEmpty()) ? null : clean(link, 20), salt, hash(pw, salt));
        if (users.putIfAbsent(u.toLowerCase(), nu) != null) throw new HttpError(400, "That username is already taken");
        return nu;
    }

    public synchronized String login(String username, String pw) {
        String key = username == null ? "" : username.trim().toLowerCase();
        long now = System.currentTimeMillis();
        long[] f = fails.get(key);
        if (f != null && f[1] > now) throw new HttpError(429, "Too many failed attempts. Try again in a minute.");
        User u = users.get(key);
        boolean ok;
        if (u != null && pw != null) ok = MessageDigest.isEqual(u.hash, hash(pw, u.salt));
        else { hash(pw == null ? "" : pw, new byte[16]); ok = false; }
        if (!ok) {
            long[] nf = f == null ? new long[2] : f;
            if (++nf[0] >= 5) { nf[0] = 0; nf[1] = now + 60000; }
            fails.put(key, nf);
            throw new HttpError(401, "Wrong username or password");
        }
        fails.remove(key);
        byte[] t = new byte[24]; rnd.nextBytes(t);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
        sessions.put(token, new Session(u.username, now + SESSION_MS));
        return token;
    }

    public User userFor(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires < System.currentTimeMillis()) { sessions.remove(token); return null; }
        return users.get(s.user.toLowerCase());
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    public List<User> list() {
        List<User> l = new ArrayList<>(users.values());
        l.sort(Comparator.comparing((User x) -> x.role).thenComparing(x -> x.username.toLowerCase()));
        return l;
    }

    public void delete(String username) {
        if (users.remove(username.toLowerCase()) == null) throw new HttpError(404, "User not found");
    }
}
