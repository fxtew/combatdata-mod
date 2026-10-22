package dev.combatdata.core;

import java.util.*;

/** Minimal JSON so the core does not depend on Gson. Objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double. */
public final class Json {
    private Json() {
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(sb, s);
        else if (o instanceof Boolean b) sb.append(b);
        else if (o instanceof Float f) sb.append(f.isNaN() || f.isInfinite() ? "null" : Float.toString(f));
        else if (o instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? "null" : Double.toString(d));
        else if (o instanceof Number n) sb.append(n);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                w(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                w(sb, x);
            }
            sb.append(']');
        } else str(sb, o.toString());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("JSON: лишние символы на позиции " + p.i);
        return v;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON: " + m + " на позиции " + i); }

        Object value() {
            if (i >= s.length()) throw err("неожиданный конец");
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return string();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return null;
                default: return num();
            }
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) throw err("ожидалось " + w);
            i += w.length();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("ожидалось :");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("неожиданный конец");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("ожидалось , или }");
            }
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw err("неожиданный конец");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("ожидалось , или ]");
            }
        }

        String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw err("ожидалась строка");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("незакрытая строка");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw err("обрыв escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("обрыв \\u");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("неизвестный escape");
                }
            }
        }

        Double num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("ожидалось значение");
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
