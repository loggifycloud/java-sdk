package io.loggify.internal;

import java.util.Collection;
import java.util.Map;

public final class Json {
  private Json() {}

  public static String quote(String value) {
    if (value == null) return "null";
    StringBuilder out = new StringBuilder(value.length() + 8);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
    return out.toString();
  }

  public static String value(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
    if (value instanceof Map<?, ?> map) return object(map);
    if (value instanceof Collection<?> list) {
      StringBuilder out = new StringBuilder("[");
      boolean first = true;
      for (Object item : list) {
        if (!first) out.append(',');
        first = false;
        out.append(value(item));
      }
      return out.append(']').toString();
    }
    return quote(String.valueOf(value));
  }

  public static String object(Map<?, ?> fields) {
    StringBuilder out = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<?, ?> entry : fields.entrySet()) {
      if (entry.getValue() == null) continue;
      if (!first) out.append(',');
      first = false;
      out.append(quote(String.valueOf(entry.getKey()))).append(':').append(value(entry.getValue()));
    }
    return out.append('}').toString();
  }
}
