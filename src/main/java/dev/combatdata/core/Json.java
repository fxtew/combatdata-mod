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
            // Most strings have no escapes: take them in one piece.
            int end = i;
            while (end < s.length() && s.charAt(end) != '"' && s.charAt(end) != '\\') end++;
            if (end < s.length() && s.charAt(end) == '"') {
                String out = s.substring(i, end);
                i = end + 1;
                return out;
            }
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

        private static final double[] POW10 = {1, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15};

        Double num() {
            int st = i, n = s.length(), digits = 0, frac = -1;
            boolean neg = i < n && s.charAt(i) == '-';
            if (neg) i++;
            long m = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    if (digits < 18) m = m * 10 + (c - '0');
                    digits++;
                    if (frac >= 0) frac++;
                } else if (c == '.' && frac < 0) {
                    frac = 0;
                } else {
                    break;
                }
                i++;
            }
            boolean plain = true;
            while (i < n && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                plain = false;
                i++;
            }
            if (st == i) throw err("ожидалось значение");
            // Up to 15 digits and no exponent: both the digits and the power of ten are exact doubles, so one correctly
            // rounded division gives the same value as Double.parseDouble, which is slow on files of many numbers.
            if (plain && digits > 0 && digits <= 15 && frac != 0) {
                double v = frac > 0 ? m / POW10[frac] : m;
                return neg ? -v : v;
            }
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
