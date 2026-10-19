package framework.eventbus;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class EventBusTest {
  public static class BaseListener {
    @EventBus.Subscribe public void inherited(Object event) {}
  }

  public static class DiscoveryListener extends BaseListener {
    @EventBus.Subscribe public void alpha(String event) {}
    @EventBus.Subscribe public void alpha(Integer event) {}
    @EventBus.Subscribe public void zeta(String event) {}
    @EventBus.Subscribe private void hidden(String event) {}
    @EventBus.Subscribe public static void staticMethod(String event) {}
    public void plain(String event) {}
  }

  public static class NoArgument {
    @EventBus.Subscribe public void invalid() {}
  }

  public static class TwoArguments {
    @EventBus.Subscribe public void invalid(String a, String b) {}
  }

  public static class PrimitiveArgument {
    @EventBus.Subscribe public void invalid(int event) {}
  }

  public static class NonVoidReturn {
    @EventBus.Subscribe public int invalid(String event) { return 1; }
  }

  public static class Recorder {
    private final String name;
    private final List<String> log;

    public Recorder(String name, List<String> log) {
      this.name = name;
      this.log = log;
    }

    @EventBus.Subscribe public void alpha(String event) { log.add(name + ":alpha:" + event); }
    @EventBus.Subscribe public void beta(String event) { log.add(name + ":beta:" + event); }
  }

  @Nested
  @Tag("blanc-q1")
  class Q1 {
    @Test void annotationIsRuntimeAndMethodOnly() {
      var retention = EventBus.Subscribe.class.getAnnotation(Retention.class);
      var target = EventBus.Subscribe.class.getAnnotation(Target.class);
      assertNotNull(retention);
      assertNotNull(target);
      assertEquals(RetentionPolicy.RUNTIME, retention.value());
      assertArrayEquals(new ElementType[] {ElementType.METHOD}, target.value());
    }

    @Test void discoveryIncludesInheritedAndOrdersOverloads() throws NoSuchMethodException {
      assertEquals(List.of(
          DiscoveryListener.class.getMethod("alpha", Integer.class),
          DiscoveryListener.class.getMethod("alpha", String.class),
          BaseListener.class.getMethod("inherited", Object.class),
          DiscoveryListener.class.getMethod("zeta", String.class)),
          EventBus.subscriberMethods(DiscoveryListener.class));
    }

    @Test void noSubscribersGivesEmptyList() {
      assertEquals(List.of(), EventBus.subscriberMethods(Object.class));
    }

    @Test void invalidMethodsAreRejected() {
      assertAll(
          () -> assertThrows(IllegalArgumentException.class, () -> EventBus.subscriberMethods(NoArgument.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> EventBus.subscriberMethods(TwoArguments.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> EventBus.subscriberMethods(PrimitiveArgument.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> EventBus.subscriberMethods(NonVoidReturn.class)));
    }

    @Test void nullTypeIsRejected() {
      assertThrows(NullPointerException.class, () -> EventBus.subscriberMethods(null));
    }
  }


  public static class AtomicListener {
    private int calls;
    @EventBus.Subscribe public void alpha(String event) { calls++; }
    @EventBus.Subscribe public int zeta(String event) { return 0; }
  }

  @Nested
  @Tag("blanc-q2")
  class Q2 {
    @Test void emptyBusReportsZero() {
      var report = new EventBus().post("hello");
      assertEquals(0, report.attempted());
      assertEquals(List.of(), report.failures());
    }

    @Test void registrationDoesNotCallMethodsAndReturnsHandle() {
      var log = new ArrayList<String>();
      var handle = new EventBus().register(new Recorder("one", log));
      assertNotNull(handle);
      assertTrue(log.isEmpty());
    }

    @Test void dispatchRespectsRegistrationAndMethodOrder() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new Recorder("one", log));
      bus.register(new Recorder("two", log));
      var report = bus.post("hello");
      assertEquals(List.of("one:alpha:hello", "one:beta:hello", "two:alpha:hello", "two:beta:hello"), log);
      assertEquals(4, report.attempted());
      assertTrue(report.failures().isEmpty());
    }

    @Test void duplicateRegistrationsAreIndependent() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      var listener = new Recorder("one", log);
      var first = bus.register(listener);
      var second = bus.register(listener);
      assertNotSame(first, second);
      assertEquals(4, bus.post("x").attempted());
      assertEquals(4, log.size());
    }

    @Test void incompatibleEventIsIgnoredAndEmptyListenerIsAccepted() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new Object());
      bus.register(new Recorder("one", log));
      assertEquals(0, bus.post(42).attempted());
      assertTrue(log.isEmpty());
    }

    @Test void nullArgumentsAreRejected() {
      var bus = new EventBus();
      assertAll(
          () -> assertThrows(NullPointerException.class, () -> bus.register(null)),
          () -> assertThrows(NullPointerException.class, () -> bus.post(null)));
    }

    @Test void invalidRegistrationIsAtomic() {
      var bus = new EventBus();
      var listener = new AtomicListener();
      assertThrows(IllegalArgumentException.class, () -> bus.register(listener));
      assertEquals(0, bus.post("hello").attempted());
      assertEquals(0, listener.calls);
    }
  }


  public static class FailingListener {
    private final Throwable first;
    private final Throwable second;
    private int successfulCalls;

    public FailingListener(Throwable first, Throwable second) {
      this.first = first;
      this.second = second;
    }

    @EventBus.Subscribe public void alpha(String event) throws Throwable { throw first; }
    @EventBus.Subscribe public void beta(String event) { successfulCalls++; }
    @EventBus.Subscribe public void gamma(String event) throws Throwable { throw second; }
  }

  @Nested
  @Tag("blanc-q3")
  class Q3 {
    @Test void exceptionsAreIsolatedAndOriginalCausesArePreserved() throws NoSuchMethodException {
      var bus = new EventBus();
      var error = new AssertionError("first");
      var checked = new IOException("second");
      var listener = new FailingListener(error, checked);
      bus.register(listener);
      var report = assertDoesNotThrow(() -> bus.post("event"));
      assertEquals(3, report.attempted());
      assertEquals(1, listener.successfulCalls);
      assertEquals(2, report.failures().size());
      var first = report.failures().get(0);
      var second = report.failures().get(1);
      assertSame(listener, first.listener());
      assertSame(listener, second.listener());
      assertSame(error, first.cause());
      assertSame(checked, second.cause());
      assertEquals(FailingListener.class.getMethod("alpha", String.class), first.method());
      assertEquals(FailingListener.class.getMethod("gamma", String.class), second.method());
    }

    @Test void laterSubscribersStillReceiveTheEvent() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new FailingListener(new RuntimeException(), new Error()));
      bus.register(new Recorder("later", log));
      var report = bus.post("x");
      assertEquals(5, report.attempted());
      assertEquals(List.of("later:alpha:x", "later:beta:x"), log);
    }

    @Test void reportsAreImmutableAndIndependent() {
      var bus = new EventBus();
      bus.register(new FailingListener(new RuntimeException(), new Error()));
      var report = bus.post("x");
      var snapshot = List.copyOf(report.failures());
      assertThrows(UnsupportedOperationException.class, () -> report.failures().clear());
      bus.post("y");
      assertEquals(snapshot, report.failures());
      assertEquals(2, report.failures().size());
    }

    @Test void successfulReportAlsoHasAnImmutableFailureList() throws NoSuchMethodException {
      var report = new EventBus().post("x");
      var failure = new EventBus.Failure(this, Object.class.getMethod("toString"), new Error());
      assertThrows(UnsupportedOperationException.class,
          () -> report.failures().add(failure));
    }
  }


  public static class PolymorphicListener {
    private final List<String> log;
    private Object received;
    public PolymorphicListener(List<String> log) { this.log = log; }
    @EventBus.Subscribe public void alpha(Object event) { log.add("object"); received = event; }
    @EventBus.Subscribe public void beta(CharSequence event) { log.add("sequence"); }
    @EventBus.Subscribe public void gamma(String event) { log.add("string"); }
    @EventBus.Subscribe public void zeta(Integer event) { log.add("integer"); }
  }

  @Nested
  @Tag("blanc-q4")
  class Q4 {
    @Test void stringMatchesClassInterfaceAndObject() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      var listener = new PolymorphicListener(log);
      bus.register(listener);
      var event = new String("hello");
      assertEquals(3, bus.post(event).attempted());
      assertEquals(List.of("object", "sequence", "string"), log);
      assertSame(event, listener.received);
    }

    @Test void interfaceCompatibilityIsNotLimitedToString() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new PolymorphicListener(log));
      assertEquals(2, bus.post(new StringBuilder("hello")).attempted());
      assertEquals(List.of("object", "sequence"), log);
    }

    @Test void integerDoesNotMatchSequenceOrString() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new PolymorphicListener(log));
      assertEquals(2, bus.post(42).attempted());
      assertEquals(List.of("object", "integer"), log);
    }
  }


  @Nested
  @Tag("blanc-q5")
  class Q5 {
    @Test void closeUnregistersOnlyItsOwnRegistration() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      var listener = new Recorder("same", log);
      var first = bus.register(listener);
      var second = bus.register(listener);
      first.close();
      first.close();
      assertEquals(2, bus.post("x").attempted());
      assertEquals(List.of("same:alpha:x", "same:beta:x"), log);
      second.close();
      assertEquals(0, bus.post("y").attempted());
    }

    @Test void closeDoesNotAffectOtherListeners() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      var first = bus.register(new Recorder("one", log));
      bus.register(new Recorder("two", log));
      first.close();
      bus.post("x");
      assertEquals(List.of("two:alpha:x", "two:beta:x"), log);
    }

    @Test void listenerCanBeRegisteredAgainAfterClose() {
      var bus = new EventBus();
      var listener = new Recorder("one", new ArrayList<>());
      bus.register(listener).close();
      bus.register(listener);
      assertEquals(2, bus.post("x").attempted());
    }

    @Test void emptySubscriptionCanBeClosedRepeatedly() {
      var handle = new EventBus().register(new Object());
      assertDoesNotThrow(handle::close);
      assertDoesNotThrow(handle::close);
    }
  }


  public static class Priorities {
    private final List<String> log;
    public Priorities(List<String> log) { this.log = log; }
    @EventBus.Subscribe(priority = Integer.MIN_VALUE) public void alpha(String event) { log.add("min"); }
    @EventBus.Subscribe(priority = 10) public void beta(String event) { log.add("ten"); }
    @EventBus.Subscribe(priority = -1) public void gamma(String event) { log.add("negative"); }
    @EventBus.Subscribe(priority = Integer.MAX_VALUE) public void zeta(String event) { log.add("max"); }
  }

  public static class ActionListener {
    private final Runnable action;
    public ActionListener(Runnable action) { this.action = action; }
    @EventBus.Subscribe(priority = 100) public void onEvent(String event) { action.run(); }
  }

  @Nested
  @Tag("blanc-q6")
  class Q6 {
    @Test void prioritiesSupportIntegerExtremes() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new Priorities(log));
      assertEquals(4, bus.post("x").attempted());
      assertEquals(List.of("max", "ten", "negative", "min"), log);
    }

    @Test void prioritiesAreGlobalAndTiesAreStable() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new Recorder("one", log));
      bus.register(new Priorities(log));
      bus.register(new Recorder("two", log));
      bus.post("x");
      assertEquals(List.of("max", "ten", "one:alpha:x", "one:beta:x",
          "two:alpha:x", "two:beta:x", "negative", "min"), log);
    }

    @Test void closingDuringDispatchAffectsOnlyFollowingDispatches() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      var handle = bus.register(new Recorder("target", log));
      bus.register(new ActionListener(handle::close));
      assertEquals(3, bus.post("first").attempted());
      assertEquals(List.of("target:alpha:first", "target:beta:first"), log);
      assertEquals(1, bus.post("second").attempted());
      assertEquals(2, log.size());
    }

    @Test void registeringDuringDispatchAffectsOnlyFollowingDispatches() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      var late = new Recorder("late", log);
      bus.register(new ActionListener(new Runnable() {
        private boolean registered;
        @Override public void run() {
          if (!registered) {
            registered = true;
            bus.register(late);
          }
        }
      }));
      assertEquals(1, bus.post("first").attempted());
      assertTrue(log.isEmpty());
      assertEquals(3, bus.post("second").attempted());
      assertEquals(List.of("late:alpha:second", "late:beta:second"), log);
    }
  }


  public interface Emitter {
    EventBus.DispatchReport send(String event);
  }
  public interface ParentEmitter {
    EventBus.DispatchReport number(Integer event);
  }
  public interface ChildEmitter extends ParentEmitter {
    EventBus.DispatchReport text(String event);
  }
  public interface WrongEmitterReturn { void send(String event); }
  public interface WrongEmitterArity { EventBus.DispatchReport send(); }
  public interface WrongEmitterPrimitive { EventBus.DispatchReport send(int event); }
  public interface WrongEmitterDefault {
    default EventBus.DispatchReport send(String event) { return null; }
  }
  public interface InvalidInheritedEmitter extends WrongEmitterReturn {}
  public sealed interface SealedEmitter permits SealedImplementation {
    EventBus.DispatchReport send(String event);
  }
  public static final class SealedImplementation implements SealedEmitter {
    @Override public EventBus.DispatchReport send(String event) { return null; }
  }
  public interface EmptyEmitter {
    static void helper() {}
  }

  @Nested
  @Tag("blanc-q7")
  class Q7 {
    @Test void emitterIsAProxyAndObservesLaterRegistrations() {
      var bus = new EventBus();
      var emitter = bus.createEmitter(Emitter.class);
      assertTrue(Proxy.isProxyClass(emitter.getClass()));
      assertEquals(0, emitter.send("before").attempted());
      var log = new ArrayList<String>();
      var handle = bus.register(new Recorder("one", log));
      var report = emitter.send("hello");
      assertEquals(2, report.attempted());
      assertEquals(List.of("one:alpha:hello", "one:beta:hello"), log);
      handle.close();
      assertEquals(0, emitter.send("after").attempted());
    }

    @Test void inheritedEmissionMethodsWork() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new PolymorphicListener(log));
      var emitter = bus.createEmitter(ChildEmitter.class);
      assertEquals(2, emitter.number(42).attempted());
      assertEquals(3, emitter.text("hello").attempted());
      assertEquals(List.of("object", "integer", "object", "sequence", "string"), log);
    }

    @Test void emitterReturnsTheFailureReport() {
      var bus = new EventBus();
      var first = new IOException();
      var second = new AssertionError();
      bus.register(new FailingListener(first, second));
      var report = bus.createEmitter(Emitter.class).send("x");
      assertEquals(3, report.attempted());
      assertSame(first, report.failures().get(0).cause());
      assertSame(second, report.failures().get(1).cause());
    }

    @Test void invalidTypesAreRejectedAtCreation() {
      var bus = new EventBus();
      assertAll(
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(String.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(WrongEmitterReturn.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(WrongEmitterArity.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(WrongEmitterPrimitive.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(WrongEmitterDefault.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(InvalidInheritedEmitter.class)),
          () -> assertThrows(IllegalArgumentException.class, () -> bus.createEmitter(SealedEmitter.class)));
    }

    @Test void nullArgumentsAreRejected() {
      var bus = new EventBus();
      assertThrows(NullPointerException.class, () -> bus.createEmitter(null));
      var emitter = bus.createEmitter(Emitter.class);
      assertThrows(NullPointerException.class, () -> emitter.send(null));
    }

    @Test void objectMethodsAreUnsupported() {
      var emitter = new EventBus().createEmitter(Emitter.class);
      assertAll(
          () -> assertThrows(UnsupportedOperationException.class, emitter::toString),
          () -> assertThrows(UnsupportedOperationException.class, emitter::hashCode),
          () -> assertThrows(UnsupportedOperationException.class, () -> emitter.equals(emitter)));
    }

    @Test void emptyInterfaceWithStaticMethodIsAllowed() {
      var emitter = new EventBus().createEmitter(EmptyEmitter.class);
      assertTrue(Proxy.isProxyClass(emitter.getClass()));
    }
  }


  public static class NumberRecorder {
    private final List<Integer> events;

    public NumberRecorder(List<Integer> events) {
      this.events = events;
    }

    @EventBus.Subscribe public void onNumber(Integer event) {
      events.add(event);
    }
  }

  @Nested
  @Tag("blanc-q8")
  class Q8 {
    @Test void warmedClassKeepsEachListenerIndependent() {
      // Warm the shared cache with a different instance and a different bus.
      var warmup = new EventBus();
      warmup.register(new Recorder("warmup", new ArrayList<>())).close();

      var bus = new EventBus();
      var firstLog = new ArrayList<String>();
      var secondLog = new ArrayList<String>();
      var first = bus.register(new Recorder("first", firstLog));
      bus.register(new Recorder("second", secondLog));

      assertEquals(4, bus.post("one").attempted());
      assertEquals(List.of("first:alpha:one", "first:beta:one"), firstLog);
      assertEquals(List.of("second:alpha:one", "second:beta:one"), secondLog);

      first.close();
      assertEquals(2, bus.post("two").attempted());
      assertEquals(2, firstLog.size());
      assertEquals(List.of("second:alpha:one", "second:beta:one",
          "second:alpha:two", "second:beta:two"), secondLog);
    }

    @Test void sharedMetadataDoesNotShareRegistrationsBetweenBuses() {
      var firstBus = new EventBus();
      var secondBus = new EventBus();
      var log = new ArrayList<String>();
      var listener = new Recorder("shared", log);
      var firstHandle = firstBus.register(listener);

      assertEquals(0, secondBus.post("before").attempted());
      var secondHandle = secondBus.register(listener);
      firstHandle.close();
      firstHandle.close();

      assertEquals(0, firstBus.post("first").attempted());
      assertEquals(2, secondBus.post("second").attempted());
      assertEquals(List.of("shared:alpha:second", "shared:beta:second"), log);

      secondHandle.close();
      assertEquals(0, secondBus.post("after").attempted());
    }

    @Test void differentClassesKeepTheirOwnSubscriberMethods() {
      var warmup = new EventBus();
      warmup.register(new Recorder("warmup", new ArrayList<>()));
      warmup.register(new NumberRecorder(new ArrayList<>()));

      var bus = new EventBus();
      var textEvents = new ArrayList<String>();
      var numberEvents = new ArrayList<Integer>();
      bus.register(new Recorder("text", textEvents));
      bus.register(new NumberRecorder(numberEvents));

      var textReport = bus.post("hello");
      var numberReport = bus.post(42);
      assertEquals(2, textReport.attempted());
      assertEquals(1, numberReport.attempted());
      assertTrue(textReport.failures().isEmpty());
      assertTrue(numberReport.failures().isEmpty());
      assertEquals(List.of("text:alpha:hello", "text:beta:hello"), textEvents);
      assertEquals(List.of(42), numberEvents);
    }

    @Test void failedMetadataValidationNeverCreatesAPartialRegistration() {
      var firstBus = new EventBus();
      var secondBus = new EventBus();
      var invalid = new AtomicListener();

      assertThrows(IllegalArgumentException.class, () -> firstBus.register(invalid));
      assertThrows(IllegalArgumentException.class, () -> firstBus.register(invalid));
      assertThrows(IllegalArgumentException.class, () -> secondBus.register(new AtomicListener()));
      assertEquals(0, firstBus.post("first").attempted());
      assertEquals(0, secondBus.post("second").attempted());
      assertEquals(0, invalid.calls);

      var log = new ArrayList<String>();
      firstBus.register(new Recorder("valid", log));
      assertEquals(2, firstBus.post("ok").attempted());
      assertEquals(List.of("valid:alpha:ok", "valid:beta:ok"), log);
    }
  }


  public static class ContextObserver {
    private final Consumer<Object> action;

    public ContextObserver(Consumer<Object> action) {
      this.action = action;
    }

    @EventBus.Subscribe public void onEvent(Object event) {
      action.accept(event);
    }
  }

  @Nested
  @Tag("blanc-q9")
  class Q9 {
    @Test void contextIsUnavailableOutsideDispatch() {
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
      new EventBus().post("no listeners");
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void everyListenerSeesTheBusAndOriginalEventAndContextIsRemoved() {
      var bus = new EventBus();
      var observed = new ArrayList<EventBus.DispatchContext>();
      bus.register(new ContextObserver(event -> observed.add(EventBus.currentDispatch())));
      bus.register(new ContextObserver(event -> observed.add(EventBus.currentDispatch())));
      var event = new Object();

      var report = bus.post(event);
      assertTrue(report.failures().isEmpty());
      assertEquals(2, observed.size());
      for (var context : observed) {
        assertSame(bus, context.bus());
        assertSame(event, context.event());
      }
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void contextIsRemovedAfterSubscriberFailures() {
      var bus = new EventBus();
      bus.register(new FailingListener(new IOException(), new AssertionError()));
      var report = bus.post("failure");
      assertEquals(2, report.failures().size());
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void nestedPostOnTheSameBusRestoresTheOuterContext() {
      var bus = new EventBus();
      var contexts = new ArrayList<EventBus.DispatchContext>();
      bus.register(new ContextObserver(event -> {
        contexts.add(EventBus.currentDispatch());
        if (event instanceof String) {
          var innerReport = bus.post(42);
          assertTrue(innerReport.failures().isEmpty());
          contexts.add(EventBus.currentDispatch());
        }
      }));

      var outerReport = bus.post("outer");
      assertTrue(outerReport.failures().isEmpty());
      assertEquals(3, contexts.size());
      assertEquals("outer", contexts.get(0).event());
      assertEquals(42, contexts.get(1).event());
      assertSame(contexts.get(0), contexts.get(2));
      assertSame(bus, contexts.get(1).bus());
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void nestedPostOnAnotherBusRestoresContextEvenAfterFailures() {
      var outerBus = new EventBus();
      var innerBus = new EventBus();
      var contexts = new ArrayList<EventBus.DispatchContext>();
      innerBus.register(new FailingListener(new IOException(), new Error()));
      innerBus.register(new ContextObserver(event -> contexts.add(EventBus.currentDispatch())));
      outerBus.register(new ContextObserver(event -> {
        var before = EventBus.currentDispatch();
        contexts.add(before);
        assertEquals(2, innerBus.post("inner").failures().size());
        assertSame(before, EventBus.currentDispatch());
        assertThrows(NullPointerException.class, () -> innerBus.post(null));
        assertSame(before, EventBus.currentDispatch());
      }));

      assertTrue(outerBus.post("outer").failures().isEmpty());
      assertEquals(2, contexts.size());
      assertSame(outerBus, contexts.get(0).bus());
      assertSame(innerBus, contexts.get(1).bus());
      assertEquals("inner", contexts.get(1).event());
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void anotherThreadDoesNotInheritOrOverwriteTheActiveContext() {
      var bus = new EventBus();
      bus.register(new ContextObserver(event -> {
        var outerContext = EventBus.currentDispatch();
        CompletableFuture.runAsync(() -> {
          assertThrows(IllegalStateException.class, EventBus::currentDispatch);
          var otherBus = new EventBus();
          otherBus.register(new ContextObserver(otherEvent -> {
            assertSame(otherBus, EventBus.currentDispatch().bus());
            assertEquals("other thread", EventBus.currentDispatch().event());
          }));
          assertTrue(otherBus.post("other thread").failures().isEmpty());
          assertThrows(IllegalStateException.class, EventBus::currentDispatch);
        }).orTimeout(5, TimeUnit.SECONDS).join();
        assertSame(outerContext, EventBus.currentDispatch());
      }));

      var report = bus.post("main thread");
      assertTrue(report.failures().isEmpty(), () -> report.failures().toString());
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }
  }

  public record Inspection(List<ConstructedListener> listeners) {}

  public static class ConstructedListener {
    private int calls;

    public ConstructedListener() {}

    @EventBus.Subscribe public void inspect(Inspection event) {
      calls++;
      event.listeners().add(this);
    }
  }

  public static class PrivateConstructorListener {
    private PrivateConstructorListener() {}
    @EventBus.Subscribe public void onEvent(String event) {}
  }

  public static class ArgumentConstructorListener {
    public ArgumentConstructorListener(String argument) {}
    @EventBus.Subscribe public void onEvent(String event) {}
  }

  public abstract static class AbstractConstructedListener {
    public AbstractConstructedListener() {}
    @EventBus.Subscribe public void onEvent(String event) {}
  }

  public static class InvalidConstructedListener {
    private static int constructions;
    public InvalidConstructedListener() { constructions++; }
    @EventBus.Subscribe public int invalid(String event) { return 0; }
  }

  public static class RuntimeConstructorListener {
    private static final RuntimeException FAILURE = new IllegalStateException("constructor failure");
    public RuntimeConstructorListener() { throw FAILURE; }
    @EventBus.Subscribe public void onEvent(String event) {}
  }

  public static class ErrorConstructorListener {
    private static final Error FAILURE = new AssertionError("constructor error");
    public ErrorConstructorListener() { throw FAILURE; }
    @EventBus.Subscribe public void onEvent(String event) {}
  }

  public static class CheckedConstructorListener {
    private static final IOException FAILURE = new IOException("checked constructor failure");
    public CheckedConstructorListener() throws IOException { throw FAILURE; }
    @EventBus.Subscribe public void onEvent(String event) {}
  }

  @Nested
  @Tag("blanc-q10")
  class Q10 {
    @Test void eachRegistrationConstructsOneDistinctReusableListener() {
      var bus = new EventBus();
      var firstHandle = bus.registerClass(ConstructedListener.class);
      var secondHandle = bus.registerClass(ConstructedListener.class);
      var firstEvent = new Inspection(new ArrayList<>());
      var report = bus.post(firstEvent);
      assertEquals(2, report.attempted());
      assertTrue(report.failures().isEmpty());
      assertEquals(2, firstEvent.listeners().size());
      var first = firstEvent.listeners().get(0);
      var second = firstEvent.listeners().get(1);
      assertNotSame(first, second);
      assertEquals(1, first.calls);
      assertEquals(1, second.calls);

      firstHandle.close();
      firstHandle.close();
      var secondEvent = new Inspection(new ArrayList<>());
      assertEquals(1, bus.post(secondEvent).attempted());
      assertEquals(1, secondEvent.listeners().size());
      assertSame(second, secondEvent.listeners().getFirst());
      assertEquals(1, first.calls);
      assertEquals(2, second.calls);
      secondHandle.close();
      assertEquals(0, bus.post(new Inspection(new ArrayList<>())).attempted());
    }

    @Test void missingPublicDefaultConstructorIsReportedWithoutRegistering() {
      var bus = new EventBus();
      var privateFailure = assertThrows(NoSuchMethodError.class,
          () -> bus.registerClass(PrivateConstructorListener.class));
      var argumentFailure = assertThrows(NoSuchMethodError.class,
          () -> bus.registerClass(ArgumentConstructorListener.class));
      assertInstanceOf(NoSuchMethodException.class, privateFailure.getCause());
      assertInstanceOf(NoSuchMethodException.class, argumentFailure.getCause());
      assertEquals(0, bus.post("x").attempted());
    }

    @Test void abstractClassCannotBeInstantiated() {
      var bus = new EventBus();
      var error = assertThrows(InstantiationError.class,
          () -> bus.registerClass(AbstractConstructedListener.class));
      assertInstanceOf(InstantiationException.class, error.getCause());
      assertEquals(0, bus.post("x").attempted());
    }

    @Test void invalidMethodsAreRejectedBeforeRunningTheConstructor() {
      var bus = new EventBus();
      var before = InvalidConstructedListener.constructions;
      assertThrows(IllegalArgumentException.class,
          () -> bus.registerClass(InvalidConstructedListener.class));
      assertThrows(IllegalArgumentException.class,
          () -> bus.registerClass(InvalidConstructedListener.class));
      assertEquals(before, InvalidConstructedListener.constructions);
      assertEquals(0, bus.post("x").attempted());
    }

    @Test void runtimeExceptionAndErrorFromConstructorKeepTheirIdentity() {
      var bus = new EventBus();
      assertSame(RuntimeConstructorListener.FAILURE, assertThrows(IllegalStateException.class,
          () -> bus.registerClass(RuntimeConstructorListener.class)));
      assertSame(ErrorConstructorListener.FAILURE, assertThrows(AssertionError.class,
          () -> bus.registerClass(ErrorConstructorListener.class)));
      assertEquals(0, bus.post("x").attempted());
    }

    @Test void checkedConstructorExceptionIsWrappedWithItsOriginalCause() {
      var bus = new EventBus();
      var error = assertThrows(UndeclaredThrowableException.class,
          () -> bus.registerClass(CheckedConstructorListener.class));
      assertSame(CheckedConstructorListener.FAILURE, error.getCause());
      assertEquals(0, bus.post("x").attempted());
    }

    @Test void nullClassIsRejectedAndClassWithoutSubscribersIsAllowed() {
      var bus = new EventBus();
      assertThrows(NullPointerException.class, () -> bus.registerClass(null));
      var subscription = bus.registerClass(Object.class);
      assertNotNull(subscription);
      assertDoesNotThrow(subscription::close);
      assertDoesNotThrow(subscription::close);
      assertEquals(0, bus.post("x").attempted());
    }
  }


  public record DescribedOrder(long id, double amount) {
    @Override public String toString() {
      throw new AssertionError("The formatter must not call the record's toString");
    }
  }

  public record DescriptionOrder(int zeta, String alpha) {}
  public record EmptyDescription() {}
  public record NullableDescription(String name) {}
  public record AdjustedDescription(int value) {
    @Override public int value() { return value + 1; }
  }
  public record ThrowingDescription(RuntimeException failure) {
    @Override public RuntimeException failure() { throw failure; }
  }
  public record ErrorDescription(Error failure) {
    @Override public Error failure() { throw failure; }
  }

  @Nested
  @Tag("blanc-q11")
  class Q11 {
    @Test void descriptionUsesComponentNamesAndValuesWithoutRecordToString() {
      assertEquals("DescribedOrder[id=42, amount=19.5]",
          EventBus.describeEvent(new DescribedOrder(42, 19.5)));
    }

    @Test void declarationOrderIsPreservedInsteadOfAlphabeticalOrder() {
      assertEquals("DescriptionOrder[zeta=7, alpha=hello]",
          EventBus.describeEvent(new DescriptionOrder(7, "hello")));
    }

    @Test void emptyRecordAndNullComponentAreSupported() {
      assertEquals("EmptyDescription[]", EventBus.describeEvent(new EmptyDescription()));
      assertEquals("NullableDescription[name=null]", EventBus.describeEvent(new NullableDescription(null)));
    }

    @Test void valuesAreReadThroughAccessors() {
      assertEquals("AdjustedDescription[value=42]", EventBus.describeEvent(new AdjustedDescription(41)));
    }

    @Test void cacheDoesNotReuseValuesFromAnEarlierInstance() {
      var first = new DescribedOrder(1, 10.5);
      var second = new DescribedOrder(2, 20.5);
      assertEquals("DescribedOrder[id=1, amount=10.5]", EventBus.describeEvent(first));
      assertEquals("DescribedOrder[id=2, amount=20.5]", EventBus.describeEvent(second));
      assertEquals("DescribedOrder[id=1, amount=10.5]", EventBus.describeEvent(first));
    }

    @Test void accessorFailuresKeepTheirOriginalIdentity() {
      var runtime = new IllegalStateException("accessor failure");
      var error = new AssertionError("accessor error");
      assertSame(runtime, assertThrows(IllegalStateException.class,
          () -> EventBus.describeEvent(new ThrowingDescription(runtime))));
      assertSame(error, assertThrows(AssertionError.class,
          () -> EventBus.describeEvent(new ErrorDescription(error))));
    }

    @Test void nullAndNonRecordEventsAreRejectedWithoutPublishing() {
      assertThrows(NullPointerException.class, () -> EventBus.describeEvent(null));
      assertThrows(IllegalArgumentException.class, () -> EventBus.describeEvent("text"));
      var bus = new EventBus();
      var events = new ArrayList<Object>();
      bus.register(new ContextObserver(events::add));
      EventBus.describeEvent(new EmptyDescription());
      assertTrue(events.isEmpty());
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }
  }

  public static class TypedTextListener implements Runnable, EventBus.Listener<String> {
    private final List<String> events;
    public TypedTextListener(List<String> events) { this.events = events; }
    @Override public void run() {}
    @Override public void onEvent(String event) { events.add("typed:" + event); }
  }

  public static class TypedSequenceListener implements EventBus.Listener<CharSequence> {
    private final List<CharSequence> events;
    public TypedSequenceListener(List<CharSequence> events) { this.events = events; }
    @Override public void onEvent(CharSequence event) { events.add(event); }
  }

  public static class TypedArrayListener implements EventBus.Listener<String[]> {
    private final List<String[]> events;
    public TypedArrayListener(List<String[]> events) { this.events = events; }
    @Override public void onEvent(String[] event) { events.add(event); }
  }

  public static class TypedFailingListener implements EventBus.Listener<String> {
    private final Throwable failure;
    public TypedFailingListener(Throwable failure) { this.failure = failure; }
    @Override public void onEvent(String event) throws Throwable { throw failure; }
  }

  public static class UnresolvedListener<E> implements EventBus.Listener<E> {
    @Override public void onEvent(E event) {}
  }

  public static class ParameterizedListener implements EventBus.Listener<List<String>> {
    @Override public void onEvent(List<String> event) {}
  }

  @SuppressWarnings("rawtypes")
  public static class RawListener implements EventBus.Listener {
    @Override public void onEvent(Object event) {}
  }

  public static class InheritedTypedListener extends TypedTextListener {
    public InheritedTypedListener(List<String> events) { super(events); }
  }

  public interface IndirectListener extends EventBus.Listener<String> {}
  public static class IndirectTypedListener implements IndirectListener {
    @Override public void onEvent(String event) {}
  }

  @Nested
  @Tag("blanc-q12")
  class Q12 {
    @Test void genericArgumentIsFoundAmongSeveralInterfaces() {
      assertSame(String.class, EventBus.listenerEventType(TypedTextListener.class));
      assertSame(CharSequence.class, EventBus.listenerEventType(TypedSequenceListener.class));
      assertSame(String[].class, EventBus.listenerEventType(TypedArrayListener.class));
    }

    @Test void onlyCompatibleEventsReachTheTypedListener() {
      var bus = new EventBus();
      var events = new ArrayList<String>();
      bus.registerTyped(new TypedTextListener(events));
      assertEquals(0, bus.post(42).attempted());
      var report = bus.post("hello");
      assertEquals(1, report.attempted());
      assertTrue(report.failures().isEmpty());
      assertEquals(List.of("typed:hello"), events);
    }

    @Test void interfaceAndArrayArgumentsUseNormalJavaCompatibility() {
      var bus = new EventBus();
      var sequences = new ArrayList<CharSequence>();
      var arrays = new ArrayList<String[]>();
      bus.registerTyped(new TypedSequenceListener(sequences));
      bus.registerTyped(new TypedArrayListener(arrays));
      var sequence = new StringBuilder("hello");
      var array = new String[] {"one", "two"};
      assertEquals(1, bus.post(sequence).attempted());
      assertEquals(1, bus.post(array).attempted());
      assertSame(sequence, sequences.getFirst());
      assertSame(array, arrays.getFirst());
      assertEquals(0, bus.post(new Object()).attempted());
    }

    @Test void typedAndAnnotatedListenersSharePriorityAndRegistrationOrder() {
      var bus = new EventBus();
      var log = new ArrayList<String>();
      bus.register(new Recorder("annotated", log));
      bus.registerTyped(new TypedTextListener(log));
      bus.register(new Priorities(log));
      var report = bus.post("x");
      assertEquals(7, report.attempted());
      assertTrue(report.failures().isEmpty());
      assertEquals(List.of("max", "ten", "annotated:alpha:x", "annotated:beta:x",
          "typed:x", "negative", "min"), log);
    }

    @Test void typedSubscriptionsRemainIndependentAndCloseIsIdempotent() {
      var bus = new EventBus();
      var events = new ArrayList<String>();
      var listener = new TypedTextListener(events);
      var first = bus.registerTyped(listener);
      var second = bus.registerTyped(listener);
      assertEquals(2, bus.post("before").attempted());
      first.close();
      first.close();
      assertEquals(1, bus.post("after").attempted());
      assertEquals(List.of("typed:before", "typed:before", "typed:after"), events);
      second.close();
      assertEquals(0, bus.post("closed").attempted());
    }

    @Test void typedFailuresKeepCausesAndOtherListenersStillRun() throws NoSuchMethodException {
      var bus = new EventBus();
      var checked = new IOException("checked");
      var error = new AssertionError("error");
      var first = new TypedFailingListener(checked);
      var second = new TypedFailingListener(error);
      var log = new ArrayList<String>();
      bus.registerTyped(first);
      bus.registerTyped(second);
      bus.register(new Recorder("later", log));
      var report = bus.post("x");
      assertEquals(4, report.attempted());
      assertEquals(2, report.failures().size());
      assertSame(first, report.failures().get(0).listener());
      assertSame(checked, report.failures().get(0).cause());
      assertSame(second, report.failures().get(1).listener());
      assertSame(error, report.failures().get(1).cause());
      assertEquals(EventBus.Listener.class.getMethod("onEvent", Object.class),
          report.failures().get(0).method());
      assertEquals(List.of("later:alpha:x", "later:beta:x"), log);
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void anonymousListenerRetainsItsGenericDeclarationAndSeesDispatchContext() {
      var bus = new EventBus();
      var seen = new ArrayList<String>();
      var listener = new EventBus.Listener<String>() {
        @Override public void onEvent(String event) {
          assertSame(bus, EventBus.currentDispatch().bus());
          assertSame(event, EventBus.currentDispatch().event());
          seen.add(event);
        }
      };
      bus.registerTyped(listener);
      assertTrue(bus.createEmitter(Emitter.class).send("hello").failures().isEmpty());
      assertEquals(List.of("hello"), seen);
      assertThrows(IllegalStateException.class, EventBus::currentDispatch);
    }

    @Test void unsupportedGenericDeclarationsAreRejectedWithoutPartialRegistration() {
      var bus = new EventBus();
      assertThrows(IllegalArgumentException.class, () -> bus.registerTyped(new RawListener()));
      assertThrows(IllegalArgumentException.class, () -> bus.registerTyped(new UnresolvedListener<String>()));
      assertThrows(IllegalArgumentException.class, () -> bus.registerTyped(new ParameterizedListener()));
      assertThrows(IllegalArgumentException.class,
          () -> bus.registerTyped(new InheritedTypedListener(new ArrayList<>())));
      assertThrows(IllegalArgumentException.class, () -> bus.registerTyped(new IndirectTypedListener()));
      EventBus.Listener<String> lambda = event -> {};
      assertThrows(IllegalArgumentException.class, () -> bus.registerTyped(lambda));
      assertEquals(0, bus.post("x").attempted());
      assertEquals(0, bus.post(List.of("x")).attempted());
    }

    @Test void nullArgumentsAndUnrelatedClassAreRejected() {
      var bus = new EventBus();
      assertThrows(NullPointerException.class, () -> bus.registerTyped(null));
      assertThrows(NullPointerException.class, () -> EventBus.listenerEventType(null));
      assertThrows(IllegalArgumentException.class, () -> EventBus.listenerEventType(Object.class));
    }
  }

}
