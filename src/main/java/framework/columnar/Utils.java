package framework.columnar;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * Utility class providing helper methods for reflection operations.
 * Contains methods for safe constructor invocation and bean information retrieval.
 */
public final class Utils {
  private Utils() {
    throw new AssertionError();
  }

  /**
   * Creates a new instance using the provided constructor and arguments.
   * This method handles various exceptions that can occur during constructor invocation
   * and converts checked exceptions into unchecked exceptions.
   *
   * @param <T> the type of object to be created
   * @param constructor the constructor to invoke
   * @param values the arguments to pass to the constructor
   * @return a new instance of type T created by the constructor
   * @throws AssertionError if instantiation fails
   * @throws IllegalAccessError if the constructor is inaccessible
   * @throws RuntimeException if the constructor throws a RuntimeException
   * @throws Error if the constructor throws an Error
   * @throws UndeclaredThrowableException for any other checked exceptions thrown by the constructor
   */
  public static <T> T constructorNewInstance(Constructor<T> constructor, Object[] values) {
    try {
      return constructor.newInstance(values);
    } catch (InstantiationException e) {
      throw new AssertionError(e);
    } catch (IllegalAccessException e) {
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

  /**
   * Retrieves JavaBeans information for the specified class.
   * This method wraps {@link Introspector#getBeanInfo(Class)} and converts
   * the checked {@link java.beans.IntrospectionException} into an unchecked
   * {@link IllegalArgumentException} with a descriptive message.
   *
   * @param type the class for which bean information is to be introspected
   * @return a BeanInfo object describing the specified class
   * @throws IllegalArgumentException if the class is not a valid JavaBean or introspection fails
   */
  public static BeanInfo getBeanInfo(Class<?> type) {
    try {
      return Introspector.getBeanInfo(type);
    } catch (IntrospectionException e) {
      throw new IllegalArgumentException("not a bean type " + type.getName(), e);
    }
  }
}
