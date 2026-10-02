package com.mediroute;

import java.util.*;

/** Tiny JSON writer (Maps, Lists, Strings, numbers, booleans, enums). */
public class Json {
    public static String write(Object o) { StringBuilder b = new StringBuilder(); w(b, o); return b.toString(); }

    static void w(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) b.append(o);
        else if (o instanceof Double) b.append(String.format(Locale.ROOT, "%.6f", (Double) o));
        else if (o instanceof Map) {
            b.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(','); first = false;
                str(b, String.valueOf(e.getKey())); b.append(':'); w(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('['); boolean first = true;
            for (Object x : (Iterable<?>) o) { if (!first) b.append(','); first = false; w(b, x); }
            b.append(']');
        } else str(b, o.toString());
    }

    static void str(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            if (c == '"') b.append("\\\""); else if (c == '\\') b.append("\\\\");
            else if (c < 32) b.append(' '); else b.append(c);
        }
        b.append('"');
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- parser ----
    public static Object parse(String text) { return new Parser(text).value(); }

    static class Parser {
        final String s; int i = 0;
        Parser(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws(); char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad json");
            return Double.parseDouble(s.substring(st, i));
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); ws(); i++; // colon
                m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>(); i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }
        String str() {
            StringBuilder b = new StringBuilder(); i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }
}
