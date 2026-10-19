package framework.minijunit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class TestRunnerTest {

  // -- classes to be tested

  public static class EmptyTestClass {}

  public static class SinglePassingTest {
    @TestRunner.Test
    public void shouldPass() { /* passes */ }
  }

  public static class SingleFailingTest {
    @TestRunner.Test
    public void shouldFail() {
      throw new AssertionError("intentional failure");
    }
  }

  public static class MultipleTests {
    @TestRunner.Test public void first()  { /* passes */ }
    @TestRunner.Test public void second() { throw new RuntimeException("boom"); }
    @TestRunner.Test public void third()  { /* passes */ }
  }

  public static class NoAnnotationMethods {
    public void notATest() { throw new RuntimeException("should not run"); }
    private void alsoNotATest() { throw new RuntimeException("should not run"); }
  }

  public static class MixedAnnotations {
    @TestRunner.Test public void isATest()     { /* passes */ }
    public void plainMethod()                  { throw new RuntimeException("should not run"); }
  }

  public static class ThrowsCheckedException {
    @TestRunner.Test
    public void throwsChecked() throws Exception {
      throw new Exception("checked exception");
    }
  }

  public static class ThrowsError {
    @TestRunner.Test
    public void throwsError() {
      throw new StackOverflowError();
    }
  }

  public static class ThrowsNullPointerException {
    @TestRunner.Test
    public void throwsNPE() {
      throw new NullPointerException("npe");
    }
  }

  public static class ManyMethods {
    @TestRunner.Test public void alpha() { /* passes */ }
    @TestRunner.Test public void beta()  { throw new IllegalStateException(); }
    @TestRunner.Test public void gamma() { /* passes */ }
    @TestRunner.Test public void delta() { /* passes */ }
    @TestRunner.Test public void epsilon() { throw new IllegalStateException(); }
    @TestRunner.Test public void zeta() { /* passes */ }
    @TestRunner.Test public void eta() { /* passes */ }
  }


  // -- tests

  @Nested
  public class Q1 {

    @Test
    @DisplayName("returns empty plan for class with no @Test methods")
    public void emptyPlanForEmptyClass() {
      var plan = TestRunner.plan(EmptyTestClass.class);
      assertTrue(plan.tests().isEmpty());
    }

    @Test
    @DisplayName("returns empty plan for class with no @Test annotations")
    public void emptyPlanForUnannotatedMethods() {
      var plan = TestRunner.plan(NoAnnotationMethods.class);
      assertTrue(plan.tests().isEmpty());
    }

    @Test
    @DisplayName("detects a single @Test method")
    public void singleTestMethod() {
      var plan = TestRunner.plan(SinglePassingTest.class);
      assertEquals(1, plan.tests().size());
    }

    @Test
    @DisplayName("detects multiple @Test methods")
    public void multipleTestMethods() {
      var plan = TestRunner.plan(MultipleTests.class);
      assertEquals(3, plan.tests().size());
    }

    @Test
    @DisplayName("ignores @Before, @After, and unannotated methods")
    public void ignoresNonTestAnnotations() {
      var plan = TestRunner.plan(MixedAnnotations.class);
      assertEquals(1, plan.tests().size());
    }


    @Test
    @DisplayName("throws NullPointerException for null class")
    public void throwsNpeForNullClass() {
      assertThrows(NullPointerException.class, () -> TestRunner.runTest(null));
    }

    @Test
    @DisplayName("returns empty result map for class with no @Test methods")
    public void emptyResultsForEmptyClass() {
      var result = TestRunner.runTest(EmptyTestClass.class);
      assertTrue(result.results().isEmpty());
    }

    @Test
    @DisplayName("result map contains an entry for every @Test method")
    public void resultMapHasOneEntryPerTest() {
      var result = TestRunner.runTest(MultipleTests.class);
      assertEquals(3, result.results().size());
    }

    @Test
    @DisplayName("result map keys match method names")
    public void resultMapKeysAreMethodNames() {
      var result = TestRunner.runTest(MultipleTests.class);
      assertTrue(result.results().containsKey("first"));
      assertTrue(result.results().containsKey("second"));
      assertTrue(result.results().containsKey("third"));
    }

    @Test
    @DisplayName("passing test produces a Success result")
    public void passingTestIsSuccess() {
      var result = TestRunner.runTest(SinglePassingTest.class);
      assertInstanceOf(TestRunner.Success.class, result.results().get("shouldPass"));
    }

    @Test
    @DisplayName("all passing tests in multi-test class produce Success")
    public void allPassingTestsAreSuccess() {
      var result = TestRunner.runTest(MultipleTests.class);
      assertInstanceOf(TestRunner.Success.class, result.results().get("first"));
      assertInstanceOf(TestRunner.Success.class, result.results().get("third"));
    }

    @Test
    @DisplayName("Success record equality holds")
    public void successRecordEquality() {
      assertEquals(new TestRunner.Success(), new TestRunner.Success());
    }

    @Test
    @DisplayName("only @Test-annotated methods appear in results")
    public void onlyTestAnnotatedMethodsRun() {
      var result = TestRunner.runTest(MixedAnnotations.class);
      assertEquals(1, result.results().size());
      assertTrue(result.results().containsKey("isATest"));
    }

    @Test
    @DisplayName("plain (unannotated) method is not executed as a test")
    public void plainMethodNotInResults() {
      var result = TestRunner.runTest(MixedAnnotations.class);
      assertFalse(result.results().containsKey("plainMethod"));
    }
  }


  @Nested
  public class Q2 {
    @Test
    @DisplayName("all tests work")
    public void allTestsWork() {
      TestRunner.PlanResult planResult = TestRunner.runTest(ManyMethods.class);
      Map<String, TestRunner.TestResult> resultMap = planResult.results();
      assertInstanceOf(TestRunner.Success.class, resultMap.get("alpha"));
      assertInstanceOf(TestRunner.Failure.class, resultMap.get("beta"));
      assertInstanceOf(TestRunner.Success.class, resultMap.get("gamma"));
    }

    @Test
    @DisplayName("failing test produces a Failure result")
    public void failingTestIsFailure() {
      var result = TestRunner.runTest(SingleFailingTest.class);
      assertInstanceOf(TestRunner.Failure.class, result.results().get("shouldFail"));
    }

    @Test
    @DisplayName("Failure wraps the original throwable")
    public void failureWrapsThrowable() {
      var result = TestRunner.runTest(SingleFailingTest.class);
      var failure = (TestRunner.Failure) result.results().get("shouldFail");
      assertInstanceOf(AssertionError.class, failure.throwable());
      assertEquals("intentional failure", failure.throwable().getMessage());
    }

    @Test
    @DisplayName("checked exception is captured as Failure")
    public void checkedExceptionCaptured() {
      var result = TestRunner.runTest(ThrowsCheckedException.class);
      var failure = (TestRunner.Failure) result.results().get("throwsChecked");
      assertInstanceOf(Exception.class, failure.throwable());
      assertEquals("checked exception", failure.throwable().getMessage());
    }

    @Test
    @DisplayName("Error subclass is captured as Failure")
    public void errorSubclassCaptured() {
      var result = TestRunner.runTest(ThrowsError.class);
      var failure = (TestRunner.Failure) result.results().get("throwsError");
      assertInstanceOf(StackOverflowError.class, failure.throwable());
    }

    @Test
    @DisplayName("NullPointerException is captured as Failure")
    public void npeIsCaptured() {
      var result = TestRunner.runTest(ThrowsNullPointerException.class);
      var failure = (TestRunner.Failure) result.results().get("throwsNPE");
      assertInstanceOf(NullPointerException.class, failure.throwable());
    }

    @Test
    @DisplayName("failure of one test does not prevent other tests from running")
    public void failureIsIsolated() {
      var result = TestRunner.runTest(MultipleTests.class);

      assertInstanceOf(TestRunner.Failure.class, result.results().get("second"));
      assertInstanceOf(TestRunner.Success.class, result.results().get("first"));
      assertInstanceOf(TestRunner.Success.class, result.results().get("third"));
    }

    @Test
    @DisplayName("Failure record equality holds when wrapping equal throwables")
    public void failureRecordEquality() {
      var exception = new RuntimeException("same");
      assertEquals(new TestRunner.Failure(exception), new TestRunner.Failure(exception));
    }
  }


  @Nested
  public class Q3 {
    @Test
    @DisplayName("result map in lexical order")
    public void resultMapInLexicalOrder() {
      var result = TestRunner.runTest(ManyMethods.class);
      var keys = List.copyOf(result.results().keySet());
      var expected = List.of("alpha", "beta", "delta", "epsilon", "eta", "gamma", "zeta");
      assertEquals(expected, keys);
    }
  }


  // -- Classes to be tested
  // The classes are using static field which is ugly and will only work
  // if the implementation does not run the tests in parallel

  public static class BeforeRunsBeforeTest {
    private static final ArrayList<String> log = new ArrayList<>();

    @TestRunner.Before public void before() { log.add("before"); }
    @TestRunner.Test   public void test()   { log.add("test");   }
  }

  public static class AfterRunsAfterTest {
    private static final ArrayList<String> log = new ArrayList<>();

    @TestRunner.Test  public void test()  { log.add("test");  }
    @TestRunner.After public void after() { log.add("after"); }
  }

  public static class BeforeAndAfterOrdering {
    private static final ArrayList<String> log = new ArrayList<>();

    @TestRunner.Before public void before() { log.add("before"); }
    @TestRunner.Test   public void test()   { log.add("test");   }
    @TestRunner.After  public void after()  { log.add("after");  }
  }

  public static class BeforeAndAfterRunPerTest {
    private static int beforeCount;
    private static int afterCount;

    @TestRunner.Before public void before() { beforeCount++; }
    @TestRunner.Test   public void first()  { }
    @TestRunner.Test   public void second() { }
    @TestRunner.After  public void after()  { afterCount++; }
  }

  public static class TwoBeforeMethods {
    @TestRunner.Before public void before1() { }
    @TestRunner.Before public void before2() { }
    @TestRunner.Test   public void test()    { }
  }

  public static class TwoAfterMethods {
    @TestRunner.After public void after1() { }
    @TestRunner.After public void after2() { }
    @TestRunner.Test  public void test()   { }
  }

  // @Before throws, test should not run, result is Failure from before
  public static class BeforeThrows {
    private static boolean testRan;

    @TestRunner.Before public void before() { throw new RuntimeException("before-boom"); }
    @TestRunner.Test   public void test()   { testRan = true; }
  }

  // @Before throws, @After must still NOT run
  public static class BeforeThrowsAfterShouldNotRun {
    private static boolean afterRan;

    @TestRunner.Before public void before() { throw new RuntimeException("before-boom"); }
    @TestRunner.Test   public void test()   { }
    @TestRunner.After  public void after()  { afterRan = true; }
  }

  // @After throws, test passed, result should become Failure from after
  public static class AfterThrowsTestPasses {
    @TestRunner.Test  public void test()  { }
    @TestRunner.After public void after() { throw new RuntimeException("after-boom"); }
  }

  // @After throws, test also threw, after's exception is suppressed
  public static class BothTestAndAfterThrow {
    @TestRunner.Test  public void test()  { throw new RuntimeException("test-boom"); }
    @TestRunner.After public void after() { throw new RuntimeException("after-boom"); }
  }

  // @After runs even when the test fails
  public static class AfterRunsWhenTestFails {
    private static boolean afterRan;

    @TestRunner.Test  public void test()  { throw new RuntimeException("fail"); }
    @TestRunner.After public void after() { afterRan = true; }
  }


  // -- test classes

  @Nested
  public class Q4 {

      @Test
      @DisplayName("@Before runs before the test method")
      public void beforeRunsBeforeTest() {
        BeforeRunsBeforeTest.log.clear();
        TestRunner.runTest(BeforeRunsBeforeTest.class);
        assertEquals("before", BeforeRunsBeforeTest.log.get(0));
        assertEquals("test", BeforeRunsBeforeTest.log.get(1));
      }

      @Test
      @DisplayName("having two @Before methods throws IllegalStateException")
      public void twoBeforeMethodsThrows() {
        assertThrows(IllegalStateException.class,
            () -> TestRunner.runTest(TwoBeforeMethods.class));
      }

      @Test
      @DisplayName("@Before failure is recorded as Failure for that test")
      public void beforeFailureRecordedAsFailure() {
        BeforeThrows.testRan = false;
        var result = TestRunner.runTest(BeforeThrows.class);
        assertInstanceOf(TestRunner.Failure.class, result.results().get("test"));
      }

      @Test
      @DisplayName("@Before failure wraps the thrown exception")
      public void beforeFailureWrapsThrowable() {
        var result = TestRunner.runTest(BeforeThrows.class);
        var failure = (TestRunner.Failure) result.results().get("test");
        assertEquals("before-boom", failure.throwable().getMessage());
      }

      @Test
      @DisplayName("test method does not run when @Before throws")
      public void testDoesNotRunWhenBeforeThrows() {
        BeforeThrows.testRan = false;
        TestRunner.runTest(BeforeThrows.class);
        assertFalse(BeforeThrows.testRan);
      }

      @Test
      @DisplayName("@After does not run when @Before throws")
      public void afterDoesNotRunWhenBeforeThrows() {
        BeforeThrowsAfterShouldNotRun.afterRan = false;
        TestRunner.runTest(BeforeThrowsAfterShouldNotRun.class);
        assertFalse(BeforeThrowsAfterShouldNotRun.afterRan);
      }

      @Test
      @DisplayName("@Before is invoked once per test method")
      public void beforeRunsOncePerTest() {
        BeforeAndAfterRunPerTest.beforeCount = 0;
        BeforeAndAfterRunPerTest.afterCount = 0;
        TestRunner.runTest(BeforeAndAfterRunPerTest.class);
        assertEquals(2, BeforeAndAfterRunPerTest.beforeCount);
      }

      @Test
      @DisplayName("@After runs after the test method")
      public void afterRunsAfterTest() {
        AfterRunsAfterTest.log.clear();
        TestRunner.runTest(AfterRunsAfterTest.class);
        assertEquals("test", AfterRunsAfterTest.log.get(0));
        assertEquals("after", AfterRunsAfterTest.log.get(1));
      }

      @Test
      @DisplayName("having two @After methods throws IllegalStateException")
      public void twoAfterMethodsThrows() {
        assertThrows(IllegalStateException.class,
            () -> TestRunner.runTest(TwoAfterMethods.class));
      }

      @Test
      @DisplayName("@After runs even when the test fails")
      public void afterRunsWhenTestFails() {
        AfterRunsWhenTestFails.afterRan = false;
        TestRunner.runTest(AfterRunsWhenTestFails.class);
        assertTrue(AfterRunsWhenTestFails.afterRan);
      }

      @Test
      @DisplayName("@After failure on a passing test produces a Failure result")
      public void afterFailureOverridesSuccess() {
        var result = TestRunner.runTest(AfterThrowsTestPasses.class);
        assertInstanceOf(TestRunner.Failure.class, result.results().get("test"));
      }

      @Test
      @DisplayName("@After failure on a passing test wraps the after exception")
      public void afterFailureWrapsThrowable() {
        var result = TestRunner.runTest(AfterThrowsTestPasses.class);
        var failure = (TestRunner.Failure) result.results().get("test");
        assertEquals("after-boom", failure.throwable().getMessage());
      }

      @Test
      @DisplayName("@After is invoked once per test method")
      public void afterRunsOncePerTest() {
        BeforeAndAfterRunPerTest.beforeCount = 0;
        BeforeAndAfterRunPerTest.afterCount = 0;
        TestRunner.runTest(BeforeAndAfterRunPerTest.class);
        assertEquals(2, BeforeAndAfterRunPerTest.afterCount);
      }

      @Test
      @DisplayName("execution order is before → test → after")
      public void executionOrder() {
        BeforeAndAfterOrdering.log.clear();
        TestRunner.runTest(BeforeAndAfterOrdering.class);
        assertEquals(List.of("before", "test", "after"), BeforeAndAfterOrdering.log);
      }
  }

  @Nested
  public class Q5 {
    @Test
    @DisplayName("@After exception is suppressed onto the test exception when both throw")
    public void afterExceptionSuppressedOntoTestException() {
      var result = TestRunner.runTest(BothTestAndAfterThrow.class);
      var failure = (TestRunner.Failure) result.results().get("test");
      assertEquals("test-boom", failure.throwable().getMessage());
      assertEquals(1, failure.throwable().getSuppressed().length);
      assertEquals("after-boom", failure.throwable().getSuppressed()[0].getMessage());
    }
  }


  // -- Classes to be tested

  public static class WithSingleNestedClass {
    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void innerTest() { }
    }
  }

  public static class WithUnannotatedInnerClass {
    public class NotNested {
      @TestRunner.Test public void shouldNotRun() {
        throw new RuntimeException("should not run");
      }
    }
  }

  public static class WithOuterAndNestedTests {
    @TestRunner.Test public void outerTest() { }

    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void innerTest() { }
    }
  }

  public static class WithNamedNestedClass {
    @TestRunner.Nested
    public class MyNested {
      @TestRunner.Test public void myTest() { }
    }
  }

  public static class WithFailingNestedTest {
    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void failingTest() {
        throw new RuntimeException("nested-boom");
      }
    }
  }

  public static class WithMultipleNestedTests {
    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void first()  { }
      @TestRunner.Test public void second() { throw new RuntimeException("fail"); }
      @TestRunner.Test public void third()  { }
    }
  }

  public static class WithTwoNestedClasses {
    @TestRunner.Nested
    public class FirstNested {
      @TestRunner.Test public void testA() { }
    }
    @TestRunner.Nested
    public class SecondNested {
      @TestRunner.Test public void testB() { }
    }
  }

  public static class WithNestedBefore {
    @TestRunner.Nested
    public class Inner {
      private static boolean beforeRan;

      @TestRunner.Before public void before() { beforeRan = true; }
      @TestRunner.Test   public void test()   { }
    }
  }

  public static class WithNestedAfter {
    @TestRunner.Nested
    public class Inner {
      private static boolean afterRan;

      @TestRunner.Test  public void test()  { }
      @TestRunner.After public void after() { afterRan = true; }
    }
  }

  public static class WithOuterBeforeAndNestedTest {
    private static boolean outerBeforeRan;

    @TestRunner.Before public void before() { outerBeforeRan = true; }

    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void innerTest() { }
    }
  }

  public static class WithOuterAfterAndNestedTest {
    private static boolean outerAfterRan;

    @TestRunner.After public void after() { outerAfterRan = true; }

    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void innerTest() { }
    }
  }

  public static class WithTwoBeforeInNested {
    @TestRunner.Nested
    public class Inner {
      @TestRunner.Before public void before1() { }
      @TestRunner.Before public void before2() { }
      @TestRunner.Test   public void test()    { }
    }
  }

  public static class WithOrderedExecution {
    private static final ArrayList<String> log = new ArrayList<>();

    @TestRunner.Test public void outerTest() { log.add("outer"); }

    @TestRunner.Nested
    public class Inner {
      @TestRunner.Test public void innerTest() { log.add("inner"); }
    }
  }


  @Nested
  public class Q6 {

    @Test
    @DisplayName("nested test key is prefixed with '<NestedSimpleName>.<methodName>'")
    public void nestedKeyIsPrefixed() {
      var result = TestRunner.runTest(WithNamedNestedClass.class);
      assertTrue(result.results().containsKey("MyNested.myTest"));
    }

    @Test
    @DisplayName("outer test key is just the method name (no prefix)")
    public void outerKeyHasNoPrefix() {
      var result = TestRunner.runTest(WithOuterAndNestedTests.class);
      assertTrue(result.results().containsKey("outerTest"));
    }

    @Test
    @DisplayName("both outer and nested keys are present in the same result map")
    public void outerAndNestedKeysCoexist() {
      var result = TestRunner.runTest(WithOuterAndNestedTests.class);
      assertTrue(result.results().containsKey("outerTest"));
      System.out.println(result.results());
      assertTrue(result.results().containsKey("Inner.innerTest"));
    }

    @Test
    @DisplayName("unannotated inner class is ignored entirely")
    public void unannotatedInnerClassIgnored() {
      var result = TestRunner.runTest(WithUnannotatedInnerClass.class);
      assertTrue(result.results().isEmpty());
    }

    @Test
    @DisplayName("@Nested class tests appear in results")
    public void nestedClassTestsAppear() {
      var result = TestRunner.runTest(WithSingleNestedClass.class);
      assertEquals(1, result.results().size());
      assertTrue(result.results().containsKey("Inner.innerTest"));
    }

    @Test
    @DisplayName("passing nested test produces Success")
    public void passingNestedTestIsSuccess() {
      var result = TestRunner.runTest(WithSingleNestedClass.class);
      assertInstanceOf(TestRunner.Success.class, result.results().get("Inner.innerTest"));
    }

    @Test
    @DisplayName("failing nested test produces Failure")
    public void failingNestedTestIsFailure() {
      var result = TestRunner.runTest(WithFailingNestedTest.class);
      assertInstanceOf(TestRunner.Failure.class, result.results().get("Inner.failingTest"));
    }

    @Test
    @DisplayName("failing nested test wraps the original throwable")
    public void failingNestedTestWrapsThrowable() {
      var result = TestRunner.runTest(WithFailingNestedTest.class);
      var failure = (TestRunner.Failure) result.results().get("Inner.failingTest");
      assertEquals("nested-boom", failure.throwable().getMessage());
    }

    @Test
    @DisplayName("one nested failure does not prevent other nested tests from running")
    public void nestedFailureIsIsolated() {
      var result = TestRunner.runTest(WithMultipleNestedTests.class);
      assertInstanceOf(TestRunner.Success.class, result.results().get("Inner.first"));
      assertInstanceOf(TestRunner.Failure.class, result.results().get("Inner.second"));
      assertInstanceOf(TestRunner.Success.class, result.results().get("Inner.third"));
    }

    @Test
    @DisplayName("tests from both sibling nested classes appear in results")
    public void siblingNestedClassTestsAppear() {
      var result = TestRunner.runTest(WithTwoNestedClasses.class);
      assertTrue(result.results().containsKey("FirstNested.testA"));
      assertTrue(result.results().containsKey("SecondNested.testB"));
    }

    @Test
    @DisplayName("result map contains exactly the tests from both nested classes")
    public void siblingNestedClassTestCount() {
      var result = TestRunner.runTest(WithTwoNestedClasses.class);
      assertEquals(2, result.results().size());
    }

    @Test
    @DisplayName("nested @Before runs for nested tests")
    public void nestedBeforeRunsForNestedTest() {
      WithNestedBefore.Inner.beforeRan = false;
      TestRunner.runTest(WithNestedBefore.class);
      assertTrue(WithNestedBefore.Inner.beforeRan);
    }

    @Test
    @DisplayName("nested @After runs for nested tests")
    public void nestedAfterRunsForNestedTest() {
      WithNestedAfter.Inner.afterRan = false;
      TestRunner.runTest(WithNestedAfter.class);
      assertTrue(WithNestedAfter.Inner.afterRan);
    }

    @Test
    @DisplayName("outer @Before does not fire for nested tests")
    public void outerBeforeDoesNotFireForNestedTests() {
      WithOuterBeforeAndNestedTest.outerBeforeRan = false;
      TestRunner.runTest(WithOuterBeforeAndNestedTest.class);
      assertFalse(WithOuterBeforeAndNestedTest.outerBeforeRan);
    }

    @Test
    @DisplayName("outer @After does not fire for nested tests")
    public void outerAfterDoesNotFireForNestedTests() {
      WithOuterAfterAndNestedTest.outerAfterRan = false;
      TestRunner.runTest(WithOuterAfterAndNestedTest.class);
      assertFalse(WithOuterAfterAndNestedTest.outerAfterRan);
    }

    @Test
    @DisplayName("two @Before methods in a nested class throw IllegalStateException")
    public void twoBeforeInNestedThrows() {
      assertThrows(IllegalStateException.class,
          () -> TestRunner.runTest(WithTwoBeforeInNested.class));
    }

    @Test
    @DisplayName("outer tests run before nested tests")
    public void outerRunsBeforeNested() {
      WithOrderedExecution.log.clear();
      TestRunner.runTest(WithOrderedExecution.class);
      assertEquals("outer", WithOrderedExecution.log.get(0));
      assertEquals("inner", WithOrderedExecution.log.get(1));
    }
  }
}