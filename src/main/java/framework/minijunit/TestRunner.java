package framework.minijunit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class TestRunner {
  private TestRunner() {
    throw new AssertionError();
  }

  @Retention(RUNTIME)
  @Target(METHOD)
  public @interface Test {}

  @Retention(RUNTIME)
  @Target(METHOD)
  public @interface Before {}

  @Retention(RUNTIME)
  @Target(METHOD)
  public @interface After {}

  @Retention(RUNTIME)
  @Target(TYPE)
  public @interface Nested {}

  public sealed interface TestResult {}
  public record Failure(Throwable throwable) implements TestResult {}
  public record Success() implements TestResult {}
  public record PlanResult(Map<String, TestResult> results) {}

  record UnitTest(Method method) {}
  record TestPlan(Class<?> testClass, List<UnitTest> tests, Method before, Method after,
                  List<TestPlan> nested) {
    // Preserve the constructor supplied in the initial exercise skeleton.
    TestPlan(Class<?> testClass, List<UnitTest> tests) {
      this(testClass, tests, null, null, List.of());
    }
  }

  static TestPlan plan(Class<?> testClass) {
    Objects.requireNonNull(testClass);
    var tests = new ArrayList<UnitTest>();
    Method before = null;
    Method after = null;
    for (var method : Utils.getPublicMethods(testClass)) {
      if (Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      var test = method.isAnnotationPresent(Test.class);
      var beforeMethod = method.isAnnotationPresent(Before.class);
      var afterMethod = method.isAnnotationPresent(After.class);
      if ((test || beforeMethod || afterMethod) && method.getParameterCount() != 0) {
        throw new IllegalStateException("test and lifecycle methods must have no parameters: " + method);
      }
      if (test) {
        tests.add(new UnitTest(method));
      }
      if (beforeMethod) {
        if (before != null) {
          throw new IllegalStateException("multiple @Before methods in " + testClass.getName());
        }
        before = method;
      }
      if (afterMethod) {
        if (after != null) {
          throw new IllegalStateException("multiple @After methods in " + testClass.getName());
        }
        after = method;
      }
    }
    tests.sort(Comparator.comparing(test -> test.method().getName()));
    var nested = Arrays.stream(Utils.getPublicClasses(testClass))
        .filter(type -> type.getDeclaringClass() == testClass)
        .filter(type -> type.isAnnotationPresent(Nested.class))
        .sorted(Comparator.comparing(Class::getSimpleName))
        .map(TestRunner::plan).toList();
    return new TestPlan(testClass, List.copyOf(tests), before, after, nested);
  }

  private static TestResult runUnitTest(Object instance, UnitTest test, Method before, Method after) {
    try {
      if (before != null) {
        Utils.invoke(before, instance);
      }
    } catch (Throwable throwable) {
      // A failed setup prevents both the test and its teardown from running.
      return new Failure(throwable);
    }
    Throwable failure = null;
    try {
      Utils.invoke(test.method(), instance);
    } catch (Throwable throwable) {
      failure = throwable;
    }
    try {
      if (after != null) {
        Utils.invoke(after, instance);
      }
    } catch (Throwable throwable) {
      if (failure == null) {
        failure = throwable;
      } else if (failure != throwable) {
        failure.addSuppressed(throwable);
      }
    }
    return failure == null ? new Success() : new Failure(failure);
  }

  private static void execute(TestPlan plan, Object enclosingInstance, String prefix,
                              Map<String, TestResult> results) {
    var type = plan.testClass();
    var inner = type.isMemberClass() && !Modifier.isStatic(type.getModifiers());
    var instance = inner
        ? Utils.newInstance(Utils.getPublicConstructor(type, type.getDeclaringClass()), enclosingInstance)
        : Utils.newInstance(Utils.getPublicConstructor(type));
    for (var test : plan.tests()) {
      results.put(prefix + test.method().getName(), runUnitTest(instance, test, plan.before(), plan.after()));
    }
    for (var nested : plan.nested()) {
      execute(nested, instance, prefix + nested.testClass().getSimpleName() + ".", results);
    }
  }

  public static PlanResult runTest(Class<?> testClass) {
    var plan = plan(testClass);
    var results = new TreeMap<String, TestResult>();
    execute(plan, null, "", results);
    return new PlanResult(Collections.unmodifiableSortedMap(results));
  }
}
