package framework.optimizer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AccessFlag;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

public class OptimizerTest {
  @Nested
  public class Q1 {

    @Test
    public void trackProxyIsTransparent() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<String>(List.of("foo")));
      assertAll(
          () -> assertEquals("foo", list.getFirst()),
          () -> assertEquals("foo", list.get(0)),
          () -> assertEquals(1, list.size()),
          () -> assertFalse(list.isEmpty()),
          () -> assertTrue(list.contains("foo")),
          () -> assertFalse(list.contains("bar")),
          () -> assertEquals(0, list.indexOf("foo"))
      );
    }

    @Test
    public void trackProxyExceptionsAreManagedCorrectly() {
      var optimizer = new Optimizer();
      var fakeList = new AbstractList<>() {
        @Override
        public int size() {
          throw new UnsupportedOperationException();
        }

        @Override
        public Object get(int index) {
          throw new InternalError();
        }
      };
      var list = optimizer.track(fakeList);
      assertAll(
          () -> assertThrows(UnsupportedOperationException.class, list::size),
          () -> assertThrows(InternalError.class, () -> list.get(0))
      );
    }

    @Test
    public void trackProxyIsTransparent2() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<String>());
      list.add("foo");
      list.add("bar");
      assertEquals(List.of("foo", "bar"), new ArrayList<>(list));
    }

    @Test
    public void trackProxyIsTransparent3() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<>(List.of("foo", "bar", "baz")));
      assertEquals(List.of("foo", "bar", "baz"), new ArrayList<>(list));
    }

    @Test
    public void trackProxyDNotImplementEqualsHashCodeAndToString() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<Integer>());
      assertAll(
          () -> assertThrows(UnsupportedOperationException.class, () -> list.equals(null)),
          () -> assertThrows(UnsupportedOperationException.class, list::hashCode),
          () -> assertThrows(UnsupportedOperationException.class, list::toString)
      );
    }

    @Test
    public void trackReturnsAProxy() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<>());
      assertTrue(Proxy.isProxyClass(list.getClass()));
    }

    @Test
    public void trackPrecondition() {
      var optimizer = new Optimizer();
      assertThrows(NullPointerException.class, () -> optimizer.track(null));
    }

    @Test
    public void optimizerQualityOfImplementation() {
      assertAll(
          () -> assertTrue(Optimizer.class.accessFlags().contains(AccessFlag.PUBLIC)),
          () -> assertTrue(Optimizer.class.accessFlags().contains(AccessFlag.FINAL)),
          () -> assertNotNull(Optimizer.class.getConstructor()),
          () -> assertTrue(Arrays.stream(Optimizer.class.getDeclaredFields())
              .allMatch(field -> field.accessFlags().contains(AccessFlag.PRIVATE))),
          () -> assertTrue(Arrays.stream(Optimizer.class.getDeclaredFields())
              .allMatch(field -> field.accessFlags().contains(AccessFlag.FINAL))),
          () -> assertNotNull(Optimizer.class.getMethod("track", List.class))
      );
    }
  }


  @Nested
  public class Q2 {
    @Test
    public void withALoggerAdd() {
      var builder = new StringBuilder();
      var optimizer = new Optimizer(builder::append);
      var list = optimizer.track(new ArrayList<>());
      list.add(3);
      assertEquals("Q2.withALoggerAdd()V add", builder.toString());
    }

    @Test
    public void withALoggerSize() {
      var builder = new StringBuilder();
      var optimizer = new Optimizer(builder::append);
      var list = optimizer.track(new ArrayList<>());
      list.size();
      assertEquals("Q2.withALoggerSize()V size", builder.toString());
    }

    @Test
    public void withALoggerContains() {
      var builder = new StringBuilder();
      var optimizer = new Optimizer(builder::append);
      var list = optimizer.track(new ArrayList<>());
      list.contains("foo");
      assertEquals("Q2.withALoggerContains()V contains", builder.toString());
    }

    @Test
    public void withALoggerSeveralCalls() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add);
      var list = optimizer.track(new ArrayList<>());
      list.add(3);
      list.add("foo");
      list.contains("foo");
      assertEquals("""
          Q2.withALoggerSeveralCalls()V add
          Q2.withALoggerSeveralCalls()V add
          Q2.withALoggerSeveralCalls()V contains\
          """, joiner.toString());
    }

    @Test
    public void precondition() {
      assertThrows(NullPointerException.class, () -> new Optimizer(null));
    }

    @Test
    public void optimizerQualityOfImplementation() {
      assertTrue(Arrays.stream(Optimizer.class.getConstructors())
              .anyMatch(constructor -> constructor.getParameterCount() == 1));
    }
  }


  @Nested
  public class Q3 {
    @Test
    public void computeProfile() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<String>());
      list.add("foo");
      list.add("bar");
      var profile = optimizer.profile(Optimizer.currentFrameId());
      assertEquals(Map.of("List.add", 2L), profile.asMap());
    }

    @Test
    public void computeProfileWithTwoLists() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<String>());
      list.add("foo");
      var list2 = optimizer.track(new ArrayList<String>());
      list2.add("bar");
      list2.contains("bar");
      var profile = optimizer.profile(Optimizer.currentFrameId());
      assertEquals(Map.of("List.add", 2L, "List.contains", 1L), profile.asMap());
    }

    @Test
    public void computeProfileNoCall() {
      var optimizer = new Optimizer();
      var list = optimizer.track(new ArrayList<String>());
      var profile = optimizer.profile(Optimizer.currentFrameId());
      assertEquals(Map.of(), profile.asMap());
    }

    @Test
    public void onlyOneProfilePerFrameId() {
      var optimizer = new Optimizer();
      var profile = optimizer.profile(Optimizer.currentFrameId());
      var list = optimizer.track(new ArrayList<String>());
      list.add("foo");
      list.add("bar");
      assertEquals(Map.of("List.add", 2L), profile.asMap());
    }

    @Test
    public void precondition() {
      var optimizer = new Optimizer();
      assertThrows(NullPointerException.class, () -> optimizer.profile(null));
    }

    @Test
    public void optimizerQualityOfImplementation() throws NoSuchMethodException {
      assertNotNull(Optimizer.class.getMethod("profile", Optimizer.FrameId.class));
    }
  }


  @Nested
  public class Q4 {
    @Test
    public void setFactory() {
      var optimizer = new Optimizer();
      optimizer.setFactory(Optimizer.currentFrameId(), List::copyOf);
      var list = optimizer.track(new ArrayList<String>());
      assertAll(
          () -> assertThrows(UnsupportedOperationException.class, () -> list.add("foo")),
          () -> assertFalse(Proxy.isProxyClass(list.getClass()))
      );
    }

    @Test
    public void setFactory2() {
      var optimizer = new Optimizer();
      optimizer.setFactory(Optimizer.currentFrameId(), ArrayList::new);
      var list = optimizer.track(List.of("foo"));
      list.add("bar");
      assertAll(
          () -> assertEquals(List.of("foo", "bar"), new ArrayList<>(list)),
          () -> assertFalse(Proxy.isProxyClass(list.getClass()))
      );
    }

    @Test
    public void setFactoryAfterSomeTime() {
      var optimizer = new Optimizer();
      var list = optimizer.track(List.of("foo"));

      optimizer.setFactory(Optimizer.currentFrameId(), ArrayList::new);
      var list2 = optimizer.track(List.of("bar"));
      list2.add("baz");

      assertAll(
          () -> assertEquals(List.of("foo"), new ArrayList<>(list)),
          () -> assertEquals(List.of("bar", "baz"), new ArrayList<>(list2))
      );
    }

    @Test
    public void factoryPreconditions() {
      var optimizer = new Optimizer();
      assertAll(
          () -> assertThrows(NullPointerException.class,
              () -> optimizer.setFactory(null, ArrayList::new)),
          () -> assertThrows(NullPointerException.class,
              () -> optimizer.setFactory(Optimizer.currentFrameId(), null))
      );
    }

    @Test
    public void factoryQualityOfImplementation() throws NoSuchMethodException {
      assertTrue(Optimizer.Factory.class.isAnnotationPresent(FunctionalInterface.class));
    }
  }


  @Nested
  public class Q5 {
    @Test
    public void thresholdOfTwo() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add, 2);
      var list = optimizer.track(new ArrayList<>());
      list.add("foo");
      list.add(345);
      assertEquals("""
          Q5.thresholdOfTwo()V add
          Q5.thresholdOfTwo()V add
          optimize Q5.thresholdOfTwo()V\
          """, joiner.toString());
    }

    @Test
    public void thresholdOfOne() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add, 1);
      var list = optimizer.track(List.of());
      list.contains("foo");
      assertEquals("""
          Q5.thresholdOfOne()V contains
          optimize Q5.thresholdOfOne()V\
          """, joiner.toString());
    }

    @Test
    public void thresholdOfOneHundred() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add);  // implicitly threshold=100
      var list = optimizer.track(new ArrayList<>());
      IntStream.range(0, 100).forEach(list::add);
      assertEquals("optimize Q5.thresholdOfOneHundred()V",
          joiner.toString().lines().skip(100).findFirst().orElseThrow());
    }

    @Test
    public void optimizerPrecondition() {
      assertAll(
          () -> assertThrows(NullPointerException.class,
              () -> new Optimizer(null, 1)),
          () -> assertThrows(IllegalArgumentException.class,
              () -> new Optimizer(new StringBuilder()::append, 0)),
          () -> assertThrows(IllegalArgumentException.class,
              () -> new Optimizer(new StringBuilder()::append, -1))
      );
    }

    @Test
    public void optimizerQualityOfImplementation() {
      assertTrue(Optimizer.class.getConstructors().length >= 3);
    }
  }


  @Nested
  public class Q6 {
    @Test
    public void recipe() {
      var optimizer = new Optimizer( 2);
      optimizer.addRecipe(_ -> Optional.of(ArrayList::new));

      var list = optimizer.track(new LinkedList<>());
      list.add(1);
      list.add(2);
      // optimized
      var list2 = optimizer.track(new LinkedList<>());
      list2.add(3);
      list2.add(4);
      assertAll(
          () -> assertSame(ArrayList.class, list2.getClass()),
          () -> assertEquals(List.of(3, 4), list2)
      );
    }

    @Test
    public void recipeNoAdd() {
      var optimizer = new Optimizer( 10);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(List::copyOf).filter(_ -> !profile.asMap().containsKey("List.add")));

      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
        assertAll(
            () -> assertTrue(list.contains(1)),
            () -> assertFalse(list.contains(3))
        );

        if (i > 5) {  // at that point, we know the list is optimized
          assertSame(List.of(1, 2).getClass(), list.getClass());
        }
      }
    }

    @Test
    public void recipeWithAdd() {
      var optimizer = new Optimizer( 10);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(List::copyOf).filter(_ -> !profile.asMap().containsKey("List.add")));

      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new ArrayList<>());
        list.add(1);
        list.add(2);

        if (i > 5) {  // at that point, the list should NOT be optimized
          assertNotSame(List.of(1, 2).getClass(), list.getClass());
        }
      }
    }

    @Test
    public void recipeGet() {
      var optimizer = new Optimizer( 5);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(ArrayList::new).filter(_ -> profile.asMap().containsKey("List.get")));

      List<String> last = null;
      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new LinkedList<>(List.of("foo")));
        for(var j = 0; j < list.size(); j++) {
          var value = list.get(j);
          assertNotNull(value);
        }
        last  = list;
      }
      assertSame(ArrayList.class, last.getClass());
    }

    @Test
    public void multipleRecipes() {
      var optimizer = new Optimizer( 5);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(List::copyOf).filter(_ -> !profile.asMap().containsKey("List.add")));
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(ArrayList::new).filter(_ -> profile.asMap().containsKey("List.get")));

      List<String> last = null;
      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new LinkedList<>(List.of("foo")));
        for(var j = 0; j < list.size(); j++) {
          var value = list.get(j);
          assertNotNull(value);
        }
        last  = list;
      }
      var _last = last;
      assertThrows(UnsupportedOperationException.class, () -> _last.add("bar"));
    }

    @Test
    public void multipleRecipes2() {
      var optimizer = new Optimizer( 5);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(List::copyOf).filter(_ -> !profile.asMap().containsKey("List.add")));
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(ArrayList::new).filter(_ -> profile.asMap().containsKey("List.get")));

      List<String> last = null;
      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new LinkedList<>(List.of("foo")));
        list.add("bar");
        for(var j = 0; j < list.size(); j++) {
          var value = list.get(j);
          assertNotNull(value);
        }
        last  = list;
      }
      assertSame(ArrayList.class, last.getClass());
    }

    @Test
    public void optimizerQualityOfImplementation() {
      assertEquals(4, Optimizer.class.getConstructors().length);
    }
  }


  @Nested
  public class Q7 {
    @Test
    public void profileWithIterator() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add);

      var list = optimizer.track(List.of(1, 2));
      for(var v : list) {
        // empty
      }
      assertEquals("""
          Q7.profileWithIterator()V iterator
          Q7.profileWithIterator()V hasNext
          Q7.profileWithIterator()V next
          Q7.profileWithIterator()V hasNext
          Q7.profileWithIterator()V next
          Q7.profileWithIterator()V hasNext\
          """, joiner.toString());
    }

    @Test
    public void profileWithIteratorFirst10Methods() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add, 10);

      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
        for (var iterator = list.iterator(); iterator.hasNext(); ) {
          iterator.next();
          iterator.remove();
        }
      }

      assertEquals("""
          Q7.profileWithIteratorFirst10Methods()V iterator
          Q7.profileWithIteratorFirst10Methods()V hasNext
          Q7.profileWithIteratorFirst10Methods()V next
          Q7.profileWithIteratorFirst10Methods()V remove
          Q7.profileWithIteratorFirst10Methods()V hasNext
          Q7.profileWithIteratorFirst10Methods()V next
          Q7.profileWithIteratorFirst10Methods()V remove
          Q7.profileWithIteratorFirst10Methods()V hasNext
          Q7.profileWithIteratorFirst10Methods()V iterator
          Q7.profileWithIteratorFirst10Methods()V hasNext\
          """, joiner.toString().lines().limit(10).collect(joining("\n")));
    }

    @Test
    public void profileWithIteratorRecipe() {
      var optimizer = new Optimizer( 10);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(LinkedList::new).filter(_ -> profile.asMap().containsKey("Iterator.remove")));

      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
        for(var iterator = list.iterator(); iterator.hasNext();) {
          iterator.next();
          iterator.remove();
        }

        if (i > 5) {  // at that point, we know the list is optimized
          assertSame(LinkedList.class, list.getClass());
        }
      }
    }
  }


  @Nested
  public class Q8 {
    @Test
    public void profileWithSubList() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add);

      var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
      var subList = list.subList(0, 1);
      subList.add(3);
      assertEquals("""
          Q8.profileWithSubList()V subList
          Q8.profileWithSubList()V add\
          """, joiner.toString());
    }

    @Test
    public void profileWithSubReversed() {
      var joiner = new StringJoiner("\n");
      var optimizer = new Optimizer(joiner::add);

      var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
      var subList = list.reversed();
      subList.addFirst(3);
      assertEquals("""
          Q8.profileWithSubReversed()V reversed
          Q8.profileWithSubReversed()V addFirst\
          """, joiner.toString());
    }

    @Test
    public void profileWithSeveralRecipes() {
      var optimizer = new Optimizer( 10);
      optimizer.addRecipe(profile ->
          Optional.<Optimizer.Factory>of(LinkedList::new).filter(_ -> profile.asMap().containsKey("Iterator.remove")));

      var sum = 0;
      for(var i = 0; i < 10; i++) {
        var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
        var subList = list.subList(0, list.size());
        for(var iterator = subList.iterator(); iterator.hasNext();) {
          iterator.next();
          iterator.remove();
        }

        if (i > 5) {  // at that point, we know the list is optimized
          assertSame(LinkedList.class, list.getClass());
        }
      }
    }
  }
}