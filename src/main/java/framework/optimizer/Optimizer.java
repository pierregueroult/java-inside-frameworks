package framework.optimizer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class Optimizer {
  private static final StackWalker STACK_WALKER =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  public record FrameId(String className, String methodName, String descriptor) {
    public FrameId {
      Objects.requireNonNull(className);
      Objects.requireNonNull(methodName);
      Objects.requireNonNull(descriptor);
    }

    @Override
    public String toString() {
      return className + "." + methodName + descriptor;
    }
  }

  private static FrameId findFrameId(int depth) {
    var frame = STACK_WALKER.walk(stream -> stream.skip(depth).findFirst())
        .orElseThrow(() -> new IllegalStateException("unknown caller stack frame"));
    return new FrameId(frame.getDeclaringClass().getSimpleName(), frame.getMethodName(), frame.getDescriptor());
  }

  public static FrameId currentFrameId() {
    return findFrameId(2);
  }

  public static final class Profile {
    private final HashMap<String, Long> methodCallMap = new HashMap<>();

    void add(String interfaceName, String methodName) {
      Objects.requireNonNull(interfaceName);
      Objects.requireNonNull(methodName);
      methodCallMap.merge(interfaceName + "." + methodName, 1L, Long::sum);
    }

    public Map<String, Long> asMap() {
      return Collections.unmodifiableMap(methodCallMap);
    }
  }

  @FunctionalInterface
  public interface Factory {
    <E> List<E> apply(List<E> list);
  }

  @FunctionalInterface
  public interface Recipe {
    Optional<Factory> apply(Profile profile);
  }

  private final HashMap<FrameId, Profile> profileMap = new HashMap<>();
  private final HashMap<FrameId, Factory> factoryMap = new HashMap<>();
  private final HashMap<FrameId, Integer> callCountMap = new HashMap<>();
  private final ArrayList<Recipe> recipes = new ArrayList<>();
  private final Consumer<? super String> logger;
  private final int threshold;

  public Optimizer() {
    this(message -> {}, 100);
  }

  public Optimizer(Consumer<? super String> logger) {
    this(logger, 100);
  }

  public Optimizer(int threshold) {
    this(message -> {}, threshold);
  }

  public Optimizer(Consumer<? super String> logger, int threshold) {
    this.logger = Objects.requireNonNull(logger);
    if (threshold <= 0) {
      throw new IllegalArgumentException("threshold must be positive");
    }
    this.threshold = threshold;
  }

  public Profile profile(FrameId frameId) {
    Objects.requireNonNull(frameId);
    return profileMap.computeIfAbsent(frameId, key -> new Profile());
  }

  public void setFactory(FrameId frameId, Factory factory) {
    Objects.requireNonNull(frameId);
    Objects.requireNonNull(factory);
    if (factoryMap.putIfAbsent(frameId, factory) != null) {
      throw new IllegalStateException("factory already assigned for " + frameId);
    }
  }

  public void addRecipe(Recipe recipe) {
    recipes.add(Objects.requireNonNull(recipe));
  }

  private void recordCall(FrameId frameId, Class<?> interfaceType, String methodName) {
    var count = callCountMap.getOrDefault(frameId, 0);
    if (count >= threshold) {
      return;
    }
    logger.accept(frameId + " " + methodName);
    var profile = profile(frameId);
    profile.add(interfaceType.getSimpleName(), methodName);
    callCountMap.put(frameId, count + 1);
    if (count + 1 == threshold) {
      logger.accept("optimize " + frameId);
      if (!factoryMap.containsKey(frameId)) {
        for (var recipe : recipes) {
          var factory = recipe.apply(profile);
          if (factory.isPresent()) {
            setFactory(frameId, factory.orElseThrow());
            break;
          }
        }
      }
    }
  }

  private <T> T trackingProxy(Class<T> interfaceType, Object delegate, FrameId frameId) {
    return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(),
        new Class<?>[] {interfaceType}, (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            throw new UnsupportedOperationException(method.getName());
          }
          recordCall(frameId, interfaceType, method.getName());
          var result = Utils.invokeMethod(delegate, method, args);
          var returnType = method.getReturnType();
          // Use the public return interface, not an often package-private implementation class.
          if (result != null && returnType.isInterface() && !returnType.isSealed()) {
            return trackingProxy(returnType, result, frameId);
          }
          return result;
        }));
  }

  public <E> List<E> track(List<E> list) {
    Objects.requireNonNull(list);
    var frameId = findFrameId(2);
    var factory = factoryMap.get(frameId);
    if (factory != null) {
      return factory.apply(list);
    }
    profile(frameId);
    // List.class is raw because Java has no Class<List<E>> token. The proxy delegates to List<E>.
    @SuppressWarnings("unchecked")
    var proxy = (List<E>) trackingProxy(List.class, list, frameId);
    return proxy;
  }
}
