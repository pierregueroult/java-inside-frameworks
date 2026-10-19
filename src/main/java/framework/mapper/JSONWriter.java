package framework.mapper;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JSONWriter {
  @FunctionalInterface
  private interface Generator {
    String generate(JSONWriter writer, Object value);
  }

  private final HashMap<Class<?>, Generator> generators = new HashMap<>();

  private static List<PropertyDescriptor> beanProperties(Class<?> type) {
    return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
        .filter(property -> !property.getName().equals("class"))
        .filter(property -> property.getReadMethod() != null)
        .toList();
  }

  private static List<PropertyDescriptor> recordProperties(Class<?> type) {
    return Arrays.stream(type.getRecordComponents()).map(component -> {
      try {
        return new PropertyDescriptor(component.getName(), component.getAccessor(), null);
      } catch (IntrospectionException e) {
        throw new IllegalStateException(e);
      }
    }).toList();
  }

  private static final ClassValue<Generator> GENERATORS = new ClassValue<>() {
    @Override
    protected Generator computeValue(Class<?> type) {
      if (type == String.class || type == Character.class) {
        return (writer, value) -> quote(value.toString());
      }
      if (type == Boolean.class || Number.class.isAssignableFrom(type)) {
        return (writer, value) -> value.toString();
      }
      var properties = type.isRecord() ? recordProperties(type) : beanProperties(type);
      var fields = properties.stream().<Generator>map(property -> {
        var getter = property.getReadMethod();
        var annotation = getter.getAnnotation(JSONProperty.class);
        var key = quote(annotation == null ? property.getName() : annotation.value()) + ": ";
        return (writer, bean) -> key + writer.toJSON(Utils.invokeMethod(bean, getter));
      }).toList();
      return (writer, bean) -> fields.stream()
          .map(field -> field.generate(writer, bean))
          .collect(Collectors.joining(", ", "{", "}"));
    }
  };

  private static String quote(String value) {
    var builder = new StringBuilder("\"");
    for (var i = 0; i < value.length(); i++) {
      var c = value.charAt(i);
      switch (c) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (c < 0x20) {
            builder.append(String.format("\\u%04x", (int) c));
          } else {
            builder.append(c);
          }
        }
      }
    }
    return builder.append('"').toString();
  }

  public <T> void configure(Class<T> type, Function<? super T, String> generator) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(generator);
    if (generators.putIfAbsent(type, (writer, value) -> generator.apply(type.cast(value))) != null) {
      throw new IllegalStateException("generator already registered for " + type.getName());
    }
  }

  public String toJSON(Object value) {
    if (value == null) {
      return "null";
    }
    var type = value.getClass();
    var generator = generators.get(type);
    return (generator == null ? GENERATORS.get(type) : generator).generate(this, value);
  }
}
