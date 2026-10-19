package framework.columnar;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Columnar {
  private Columnar() {
    throw new AssertionError();
  }

  private static Object primitiveArray(int size, Class<?> type) {
    Objects.requireNonNull(type);
    if (size < 0) {
      throw new IllegalArgumentException("negative size: " + size);
    }
    if (!type.isPrimitive() || type == void.class) {
      throw new IllegalArgumentException("not a primitive value type: " + type.getName());
    }
    return Array.newInstance(type, size);
  }

  public static <T> List<T> primitiveList(int size, Class<T> primitiveType) {
    var values = primitiveArray(size, primitiveType);
    return new AbstractList<>() {
      @Override
      @SuppressWarnings("unchecked")
      public T get(int index) {
        Objects.checkIndex(index, size);
        // Array.get boxes the primitive; Class.cast cannot be used with a primitive Class.
        return (T) Array.get(values, index);
      }

      @Override
      public T set(int index, T value) {
        Objects.checkIndex(index, size);
        Objects.requireNonNull(value);
        var previous = get(index);
        Array.set(values, index, value);
        return previous;
      }

      @Override
      public int size() {
        return size;
      }
    };
  }

  static <T> Constructor<T> findCanonicalConstructor(Class<T> recordType) {
    Objects.requireNonNull(recordType);
    if (!recordType.isRecord()) {
      throw new IllegalArgumentException("not a record: " + recordType.getName());
    }
    var parameterTypes = Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getType).toArray(Class<?>[]::new);
    try {
      return recordType.getConstructor(parameterTypes);
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    }
  }

  static List<Method> accessors(Class<?> recordType) {
    Objects.requireNonNull(recordType);
    if (!recordType.isRecord()) {
      throw new IllegalArgumentException("not a record: " + recordType.getName());
    }
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getAccessor).toList();
  }

  static Object accessorInvoke(Method method, Object instance) {
    Objects.requireNonNull(method);
    Objects.requireNonNull(instance);
    try {
      return method.invoke(instance);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (InvocationTargetException e) {
      var cause = e.getCause();
      switch (cause) {
        case RuntimeException exception -> throw exception;
        case Error error -> throw error;
        default -> throw new UndeclaredThrowableException(cause);
      }
    }
  }

  public static <T> List<T> recordList(int size, Class<T> recordType) {
    if (size < 0) {
      throw new IllegalArgumentException("negative size: " + size);
    }
    var constructor = findCanonicalConstructor(recordType);
    var accessors = accessors(recordType);
    // Each entry contains one primitive array, never a row object.
    var columns = accessors.stream().map(method -> primitiveArray(size, method.getReturnType())).toArray();
    return new AbstractList<>() {
      @Override
      public T get(int index) {
        Objects.checkIndex(index, size);
        var values = new Object[columns.length];
        for (var column = 0; column < columns.length; column++) {
          values[column] = Array.get(columns[column], index);
        }
        return Utils.constructorNewInstance(constructor, values);
      }

      @Override
      public T set(int index, T value) {
        Objects.checkIndex(index, size);
        Objects.requireNonNull(value);
        recordType.cast(value);
        var values = accessors.stream().map(method -> accessorInvoke(method, value)).toArray();
        var previous = get(index);
        for (var column = 0; column < columns.length; column++) {
          Array.set(columns[column], index, values[column]);
        }
        return previous;
      }

      @Override
      public int size() {
        return size;
      }
    };
  }

  static Map<Method, Integer> getterPropertyMap(List<PropertyDescriptor> properties) {
    Objects.requireNonNull(properties);
    var map = new HashMap<Method, Integer>();
    for (var index = 0; index < properties.size(); index++) {
      var getter = properties.get(index).getReadMethod();
      if (getter != null) {
        map.put(getter, index);
      }
    }
    return Collections.unmodifiableMap(map);
  }

  static Map<Method, Integer> setterPropertyMap(List<PropertyDescriptor> properties) {
    Objects.requireNonNull(properties);
    var map = new HashMap<Method, Integer>();
    for (var index = 0; index < properties.size(); index++) {
      var setter = properties.get(index).getWriteMethod();
      if (setter != null) {
        map.put(setter, index);
      }
    }
    return Collections.unmodifiableMap(map);
  }

  public static <T> List<T> proxyList(int size, Class<T> interfaceType) {
    Objects.requireNonNull(interfaceType);
    if (size < 0) {
      throw new IllegalArgumentException("negative size: " + size);
    }
    if (!interfaceType.isInterface()) {
      throw new IllegalArgumentException("not an interface: " + interfaceType.getName());
    }
    var properties = Arrays.stream(Utils.getBeanInfo(interfaceType).getPropertyDescriptors())
        .filter(property -> !property.getName().equals("class")).toList();
    var getters = getterPropertyMap(properties);
    var setters = setterPropertyMap(properties);
    var columns = properties.stream().map(property -> primitiveArray(size, property.getPropertyType())).toArray();
    return new AbstractList<>() {
      @Override
      public T get(int index) {
        Objects.checkIndex(index, size);
        // All proxies for a given index read and write the same column cells.
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(),
            new Class<?>[] {interfaceType}, (proxy, method, args) -> {
              var getterColumn = getters.get(method);
              if (getterColumn != null) {
                return Array.get(columns[getterColumn], index);
              }
              var setterColumn = setters.get(method);
              if (setterColumn != null) {
                Array.set(columns[setterColumn], index, args[0]);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            }));
      }

      @Override
      public int size() {
        return size;
      }
    };
  }
}
