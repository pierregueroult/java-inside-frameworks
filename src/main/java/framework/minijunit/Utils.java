package framework.minijunit;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;

public final class Utils {
  private Utils() {
    throw new AssertionError();
  }

  public static Constructor<?> getPublicConstructor(Class<?> type, Class<?>... parameterTypes) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(parameterTypes);
    try {
      return type.getConstructor(parameterTypes);
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    }
  }

  public static Method[] getPublicMethods(Class<?> type) {
    Objects.requireNonNull(type);
    return type.getMethods();
  }

  public static Class<?>[] getPublicClasses(Class<?> type) {
    Objects.requireNonNull(type);
    return type.getClasses();
  }

  public static Object newInstance(Constructor<?> constructor, Object... arguments) {
    Objects.requireNonNull(constructor);
    Objects.requireNonNull(arguments);
    try {
      return constructor.newInstance(arguments);
    } catch (InstantiationException e) {
      throw (InstantiationError) new InstantiationError().initCause(e);
    } catch(IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (InvocationTargetException e) {
      var cause = e.getCause();
      switch (cause) {
        case RuntimeException runtimeException -> throw runtimeException;
        case Error error -> throw error;
        case Throwable throwable -> throw new UndeclaredThrowableException(throwable);
      }
    }
  }

  public static Object invoke(Method method, Object instance, Object... arguments) throws Throwable {
    Objects.requireNonNull(method);
    Objects.requireNonNull(instance);
    Objects.requireNonNull(arguments);
    try {
      return method.invoke(instance, arguments);
    } catch (IllegalAccessException e) {
      throw new IllegalAccessError().initCause(e);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
