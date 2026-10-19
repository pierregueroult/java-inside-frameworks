package framework.optimizer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class Utils {
  private Utils() {
    throw new AssertionError();
  }

  public static Object invokeMethod(Object receiver, Method method, Object... args) {
    try {
      return method.invoke(receiver, args);
    } catch (IllegalArgumentException e) {
      throw new AssertionError(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (InvocationTargetException e) {
      throw rethrow(e.getCause());
    }
  }

  @SuppressWarnings("unchecked")   // very wrong but works
  static <T extends Throwable> AssertionError rethrow(Throwable cause) throws T {
    throw (T) cause;
  }
}
