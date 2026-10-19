package framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {
  private final HashMap<Class<?>, Supplier<?>> providers = new HashMap<>();

  public <T> void registerInstance(Class<T> type, T instance) {
    Objects.requireNonNull(instance);
    registerProvider(type, () -> instance);
  }

  public <T> void registerProvider(Class<T> type, Supplier<? extends T> provider) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(provider);
    if (providers.putIfAbsent(type, provider) != null) {
      throw new IllegalStateException("provider already registered for " + type.getName());
    }
  }

  public <T> T lookupInstance(Class<T> type) {
    Objects.requireNonNull(type);
    var provider = providers.get(type);
    if (provider == null) {
      throw new IllegalStateException("no provider for " + type.getName());
    }
    return type.cast(provider.get());
  }

  static List<PropertyDescriptor> findInjectableProperties(Class<?> type) {
    Objects.requireNonNull(type);
    return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
        .filter(property -> property.getWriteMethod() != null)
        .filter(property -> !Modifier.isStatic(property.getWriteMethod().getModifiers()))
        .filter(property -> property.getWriteMethod().isAnnotationPresent(Inject.class))
        .toList();
  }

  public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(providerClass);
    var constructors = Arrays.stream(providerClass.getConstructors())
        .filter(constructor -> constructor.isAnnotationPresent(Inject.class)).toList();
    if (constructors.size() > 1) {
      throw new IllegalStateException("multiple @Inject constructors for " + providerClass.getName());
    }
    var constructor = constructors.isEmpty() ? Utils.defaultConstructor(providerClass) : constructors.getFirst();
    var parameterTypes = constructor.getParameterTypes();
    var properties = findInjectableProperties(providerClass);
    registerProvider(type, () -> {
      var args = Arrays.stream(parameterTypes).map(this::lookupInstance).toArray();
      var instance = providerClass.cast(Utils.newInstance(constructor, args));
      for (var property : properties) {
        Utils.invokeMethod(instance, property.getWriteMethod(), lookupInstance(property.getPropertyType()));
      }
      return instance;
    });
  }

  public <T> void registerProviderClass(Class<T> providerClass) {
    registerProviderClass(providerClass, providerClass);
  }
}
