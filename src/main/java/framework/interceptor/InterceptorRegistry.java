package framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class InterceptorRegistry {
  private final HashMap<Class<? extends Annotation>, List<Interceptor>> interceptors = new HashMap<>();
  private final HashMap<Method, Invocation> invocations = new HashMap<>();

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice advice) {
    Objects.requireNonNull(advice);
    addInterceptor(annotationClass, (instance, method, args, next) -> {
      advice.before(instance, method, args);
      var result = next.proceed(instance, method, args);
      advice.after(instance, method, args, result);
      return result;
    });
  }

  public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(interceptor);
    interceptors.computeIfAbsent(annotationClass, key -> new ArrayList<>()).add(interceptor);
    // Proxies already created must also see newly registered interceptors.
    invocations.clear();
  }

  List<Interceptor> findInterceptors(Method method) {
    Objects.requireNonNull(method);
    var annotations = new LinkedHashSet<Class<? extends Annotation>>();
    for (var annotation : method.getDeclaringClass().getAnnotations()) {
      annotations.add(annotation.annotationType());
    }
    for (var annotation : method.getAnnotations()) {
      annotations.add(annotation.annotationType());
    }
    for (var parameter : method.getParameterAnnotations()) {
      for (var annotation : parameter) {
        annotations.add(annotation.annotationType());
      }
    }
    return annotations.stream().flatMap(type -> interceptors.getOrDefault(type, List.of()).stream()).toList();
  }

  static Invocation getInvocation(List<Interceptor> interceptors) {
    Objects.requireNonNull(interceptors);
    Invocation invocation = Utils::invokeMethod;
    for (var interceptor : interceptors.reversed()) {
      var next = invocation;
      invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, next);
    }
    return invocation;
  }

  public <T> T createProxy(Class<T> type, T delegate) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(delegate);
    if (!type.isInterface() || !type.isInstance(delegate)) {
      throw new IllegalArgumentException("expected an interface and a delegate implementing it");
    }
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (proxy, method, args) -> invocations.computeIfAbsent(method,
            key -> getInvocation(findInterceptors(key))).proceed(delegate, method, args)));
  }
}
