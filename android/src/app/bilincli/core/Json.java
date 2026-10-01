package app.bilincli.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bağımlılıksız küçük JSON okuyucu/yazıcı.
 * Nesneler LinkedHashMap, diziler ArrayList, sayılar Long ya da Double olarak döner.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("fazladan karakter");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("JSON nesnesi bekleniyordu");
        return (Map<String, Object>) o;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON hatası (" + i + "): " + msg);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw error("beklenmeyen son");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("beklenmeyen karakter '" + c + "'");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error(word + " bekleniyordu");
        i += word.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw error("anahtar bekleniyordu");
            String key = string();
            ws();
            if (peek() != ':') throw error("':' bekleniyordu");
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw error("',' ya da '}' bekleniyordu");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return list;
            if (c != ',') throw error("',' ya da ']' bekleniyordu");
        }
    }

    private char peek() {
        if (i >= s.length()) throw error("beklenmeyen son");
        return s.charAt(i);
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("kapanmamış metin");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("kapanmamış kaçış");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("eksik \\u");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw error("geçersiz kaçış");
            }
        }
    }

    private Object number() {
        int start = i;
        boolean real = false;
        if (s.charAt(i) == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                real = true;
                i++;
            } else break;
        }
        String t = s.substring(start, i);
        try {
            if (!real) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException big) {
                    return Double.parseDouble(t);
                }
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            throw error("geçersiz sayı " + t);
        }
    }

    // ---- yazma -------------------------------------------------------------
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            b.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d).append(".0");
            else b.append(d);
        } else if (v instanceof Map) {
            b.append('{');
            Iterator<Map.Entry<String, Object>> it = ((Map<String, Object>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            List<Object> list = (List<Object>) v;
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) b.append(',');
                write(b, list.get(k));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("JSON'a yazılamaz: " + v.getClass());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    // </script> ve satır ayraçları WebView'e güvenli aktarılsın
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // ---- erişim yardımcıları ----------------------------------------------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d)) return String.valueOf((long) d);
        }
        return String.valueOf(v);
    }

    public static Double num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static long lng(Map<String, Object> m, String key, long def) {
        Double d = num(m, key);
        return d == null ? def : Math.round(d);
    }

    public static double dbl(Map<String, Object> m, String key, double def) {
        Double d = num(m, key);
        return d == null ? def : d;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
