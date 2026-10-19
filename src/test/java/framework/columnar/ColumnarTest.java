package framework.columnar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ColumnarTest {

  @Nested
  @DisplayName("primitiveList Tests")
  public class Q1 {

    @Test
    @DisplayName("Should create list with correct capacity for int primitive type")
    public void primitiveListCreatesCorrectCapacityForInt() {
      var capacity = 10;
      List<Integer> list = Columnar.primitiveList(capacity, int.class);

      assertEquals(capacity, list.size());
    }

    @Test
    @DisplayName("Should create list with correct capacity for other primitive types")
    public void primitiveListCreatesCorrectCapacityForOtherTypes() {
      var capacity = 5;

      List<Boolean> boolList = Columnar.primitiveList(capacity, boolean.class);
      List<Byte> byteList = Columnar.primitiveList(capacity, byte.class);
      List<Character> charList = Columnar.primitiveList(capacity, char.class);
      List<Short> shortList = Columnar.primitiveList(capacity, short.class);
      List<Long> longList = Columnar.primitiveList(capacity, long.class);
      List<Float> floatList = Columnar.primitiveList(capacity, float.class);
      List<Double> doubleList = Columnar.primitiveList(capacity, double.class);

      assertEquals(capacity, boolList.size());
      assertEquals(capacity, byteList.size());
      assertEquals(capacity, charList.size());
      assertEquals(capacity, shortList.size());
      assertEquals(capacity, longList.size());
      assertEquals(capacity, floatList.size());
      assertEquals(capacity, doubleList.size());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for negative capacity")
    public void primitiveListThrowsForNegativeCapacity() {
      assertThrows(IllegalArgumentException.class,
          () -> Columnar.primitiveList(-1, int.class));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for non-primitive type")
    public void primitiveListThrowsForNonPrimitiveType() {
      assertThrows(IllegalArgumentException.class,
          () -> Columnar.primitiveList(10, String.class));
    }

    @Test
    @DisplayName("Should get and set values correctly")
    public void primitiveListGetAndSetWorkCorrectly() {
      List<Integer> list = Columnar.primitiveList(10, int.class);

      // Initial values should be 0 (default for int)
      assertEquals(0, list.get(0));

      // Test setting values
      list.set(0, 42);
      assertEquals(42, list.get(0));

      // Test setting multiple values
      list.set(1, 100);
      list.set(9, 999);
      assertEquals(100, list.get(1));
      assertEquals(999, list.get(9));
    }

    @Test
    @DisplayName("Should throw IndexOutOfBoundsException for invalid index")
    public void primitiveListThrowsForInvalidIndex() {
      List<Integer> list = Columnar.primitiveList(10, int.class);

      assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
      assertThrows(IndexOutOfBoundsException.class, () -> list.get(10));
      assertThrows(IndexOutOfBoundsException.class, () -> list.set(-1, 42));
      assertThrows(IndexOutOfBoundsException.class, () -> list.set(10, 42));
    }
  }


  @Nested
  @DisplayName("findCanonicalConstructor Tests")
  public class Q2 {

    public record TestRecord(int id, String name) {}
    public record EmptyRecord() {}
    public class NotARecord {}

    @Test
    @DisplayName("Should find constructor for valid record")
    public void findsConstructorForValidRecord() {
      var constructor = Columnar.findCanonicalConstructor(TestRecord.class);
      assertNotNull(constructor);
      assertEquals(2, constructor.getParameterCount());
      assertArrayEquals(new Class<?>[] {int.class, String.class}, constructor.getParameterTypes());
    }

    @Test
    @DisplayName("Should find constructor for empty record")
    public void findsConstructorForEmptyRecord() {
      var constructor = Columnar.findCanonicalConstructor(EmptyRecord.class);
      assertNotNull(constructor);
      assertEquals(0, constructor.getParameterCount());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for non-record class")
    public void throwsForNonRecordClass() {
      assertThrows(IllegalArgumentException.class,
          () -> Columnar.findCanonicalConstructor(NotARecord.class));
    }
  }


  @Nested
  @DisplayName("accessors Tests")
  public class Q3 {

    public record TestRecord(int id, String name) {}
    public class NotARecord {}

    @Test
    @DisplayName("Should return correct accessors for record")
    public void returnsCorrectAccessorsForRecord() {
      var accessors = Columnar.accessors(TestRecord.class);

      assertEquals(2, accessors.size());
      assertEquals("id", accessors.get(0).getName());
      assertEquals("name", accessors.get(1).getName());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for non-record class")
    public void throwsForNonRecordClass() {
      assertThrows(IllegalArgumentException.class,
          () -> Columnar.accessors(NotARecord.class));
    }
  }


  @Nested
  @DisplayName("recordList readonly Tests")
  public class Q4 {

    public record Person(int id, long salary) {}

    @Test
    @DisplayName("Should create recordList with correct capacity")
    public void recordListCreatesCorrectCapacity() {
      int capacity = 10;
      List<Person> list = Columnar.recordList(capacity, Person.class);

      assertEquals(capacity, list.size());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for negative capacity")
    public void recordListThrowsForNegativeCapacity() {
      assertThrows(IllegalArgumentException.class,
          () -> Columnar.recordList(-1, Person.class));
    }

    @Test
    @DisplayName("Should get record values correctly")
    public void recordListGetAndSetWorkCorrectly() {
      List<Person> list = Columnar.recordList(5, Person.class);

      // Initial values should have defaults
      Person initial = list.get(0);
      assertEquals(0, initial.id());
      assertEquals(0L, initial.salary());
    }

    @Test
    @DisplayName("Should throw IndexOutOfBoundsException for invalid index")
    public void recordListThrowsForInvalidIndex() {
      List<Person> list = Columnar.recordList(5, Person.class);

      assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
      assertThrows(IndexOutOfBoundsException.class, () -> list.get(5));
    }
  }


  @Nested
  @DisplayName("accessorInvoke Tests")
  public class Q5 {

    public record TestRecord(int id, String name) {}

    @Test
    @DisplayName("Should invoke accessor method correctly")
    public void invokesAccessorCorrectly() throws NoSuchMethodException {
      TestRecord record = new TestRecord(42, "Test");
      Method idMethod = TestRecord.class.getMethod("id");
      Method nameMethod = TestRecord.class.getMethod("name");

      assertEquals(42, Columnar.accessorInvoke(idMethod, record));
      assertEquals("Test", Columnar.accessorInvoke(nameMethod, record));
    }

    @Test
    @DisplayName("Should handle and rethrow exceptions correctly")
    public void handlesExceptionsCorrectly() throws NoSuchMethodException {
      // Create a method that will throw an exception when invoked
      class ExceptionThrower {
        public void throwRuntimeException() {
          throw new RuntimeException("Test exception");
        }

        public void throwError() {
          throw new Error("Test error");
        }

        public void throwCheckedException() throws Exception {
          throw new Exception("Test checked exception");
        }
      }

      var thrower = new ExceptionThrower();
      var runtimeMethod = ExceptionThrower.class.getMethod("throwRuntimeException");
      var errorMethod = ExceptionThrower.class.getMethod("throwError");
      var checkedMethod = ExceptionThrower.class.getMethod("throwCheckedException");

      // Test RuntimeException
      assertThrows(RuntimeException.class,
          () -> Columnar.accessorInvoke(runtimeMethod, thrower));

      // Test Error
      assertThrows(Error.class,
          () -> Columnar.accessorInvoke(errorMethod, thrower));

      // Test checked exception wrapped in UndeclaredThrowableException
      assertThrows(UndeclaredThrowableException.class,
          () -> Columnar.accessorInvoke(checkedMethod, thrower));
    }
  }


  @Nested
  @DisplayName("recordList read/write Tests")
  public class Q6 {

    public record Person(int id, long salary) {}

    @Test
    @DisplayName("Should get and set record values correctly")
    public void recordListGetAndSetWorkCorrectly() {
      List<Person> list = Columnar.recordList(5, Person.class);

      // Initial values should have defaults
      Person initial = list.get(0);
      assertEquals(0, initial.id());
      assertEquals(0L, initial.salary());

      // Test setting values
      Person person = new Person(42, 50_000);
      list.set(0, person);

      Person retrieved = list.get(0);
      assertEquals(42, retrieved.id());
      assertEquals(50_000, retrieved.salary());
    }

    @Test
    @DisplayName("Should throw IndexOutOfBoundsException for invalid index")
    public void recordListThrowsForInvalidIndex() {
      List<Person> list = Columnar.recordList(5, Person.class);

      Person person = new Person(1, 1000);
      assertThrows(IndexOutOfBoundsException.class, () -> list.set(-1, person));
      assertThrows(IndexOutOfBoundsException.class, () -> list.set(5, person));
    }
  }

  @Nested
  @DisplayName("getterPropertyMap Tests")
  public class Q7 {

    public interface PersonInterface {
      int getId();
      String getName();
    }

    @Test
    @DisplayName("Should create property map with correct indices")
    public void createsGetterPropertyMapWithCorrectIndices() throws IntrospectionException {
      // Create mock PropertyDescriptor list for testing
      var prop1 = new PropertyDescriptor("id", PersonInterface.class, "getId", null);
      var prop2 = new PropertyDescriptor("name", PersonInterface.class, "getName", null);
      var props = List.of(prop1, prop2);

      Map<Method, Integer> map = Columnar.getterPropertyMap(props);

      assertEquals(2, map.size());
      assertEquals(0, map.get(prop1.getReadMethod()));
      assertEquals(1, map.get(prop2.getReadMethod()));
    }
  }

  @Nested
  @DisplayName("proxyList readonly Tests")
  public class Q8 {

    public interface PersonInterface {
      int getId();
      long getSalary();
    }

    @Test
    @DisplayName("Should create proxyList with correct capacity")
    public void proxyListCreatesCorrectCapacity() {
      var capacity = 10;
      List<PersonInterface> list = Columnar.proxyList(capacity, PersonInterface.class);

      assertEquals(capacity, list.size());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for negative capacity")
    public void proxyListThrowsForNegativeCapacity() {
      assertThrows(IllegalArgumentException.class,
          () -> Columnar.proxyList(-1, PersonInterface.class));
    }

    @Test
    @DisplayName("Should create proxy that implements interface")
    public void createsProxyImplementingInterface() {
      List<PersonInterface> list = Columnar.proxyList(5, PersonInterface.class);

      var person = list.get(0);

      assertTrue(Proxy.isProxyClass(person.getClass()));
      assertTrue(person instanceof PersonInterface);
    }

    @Test
    @DisplayName("Should create proxy that implements interface")
    public void createsProxyIsCorrectlyTyped() {
      List<PersonInterface> list = Columnar.proxyList(5, PersonInterface.class);

      PersonInterface person = list.get(0);

      assertNotNull(person);
    }

    @Test
    @DisplayName("Should throw IndexOutOfBoundsException for invalid index")
    public void proxyListThrowsForInvalidIndex() {
      List<PersonInterface> list = Columnar.proxyList(5, PersonInterface.class);

      assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
      assertThrows(IndexOutOfBoundsException.class, () -> list.get(5));
    }

    @Test
    @DisplayName("Should proxy return default values for primitive types")
    public void proxyReturnsDefaultValuesForPrimitiveTypes() {
      List<PersonInterface> list = Columnar.proxyList(1, PersonInterface.class);

      PersonInterface person = list.get(0);

      assertEquals(0, person.getId());
      assertEquals(0L, person.getSalary());
    }

    @Test
    @DisplayName("Should throw UnsupportedOperationException for Object methods")
    public void throwsForObjectMethods() {
      List<PersonInterface> list = Columnar.proxyList(1, PersonInterface.class);
      PersonInterface person = list.get(0);

      assertThrows(UnsupportedOperationException.class, person::toString);
      assertThrows(UnsupportedOperationException.class, person::hashCode);
      assertThrows(UnsupportedOperationException.class, () -> person.equals(null));
    }
  }


  @Nested
  @DisplayName("proxyList read/write Tests")
  public class Q9 {

    interface TestInterface {
      int getId();
      void setId(int id);
      String getName();
      void setName(String name);
    }

    interface OnlySetter {
      void setId(int id);
    }

    @Test
    @DisplayName("Should create getter property map with correct indices")
    public void createsGetterPropertyMapWithCorrectIndices() throws IntrospectionException {
      // Create mock PropertyDescriptor list for testing
      PropertyDescriptor prop1 = new PropertyDescriptor("id", TestInterface.class, "getId", "setId");
      PropertyDescriptor prop2 = new PropertyDescriptor("name", TestInterface.class, "getName", "setName");
      List<PropertyDescriptor> props = List.of(prop1, prop2);

      Map<Method, Integer> map = Columnar.getterPropertyMap(props);

      assertEquals(2, map.size());
      assertEquals(0, map.get(prop1.getReadMethod()));
      assertEquals(1, map.get(prop2.getReadMethod()));
    }

    @Test
    @DisplayName("Should create setter property map with correct indices")
    public void createsSetterPropertyMapWithCorrectIndices() throws IntrospectionException {
      // Create mock PropertyDescriptor list for testing
      PropertyDescriptor prop1 = new PropertyDescriptor("id", TestInterface.class, "getId", "setId");
      PropertyDescriptor prop2 = new PropertyDescriptor("name", TestInterface.class, "getName", "setName");
      List<PropertyDescriptor> props = List.of(prop1, prop2);

      Map<Method, Integer> map = Columnar.setterPropertyMap(props);

      assertEquals(2, map.size());
      assertEquals(0, map.get(prop1.getWriteMethod()));
      assertEquals(1, map.get(prop2.getWriteMethod()));
    }

    @Test
    @DisplayName("Should handle properties without setters in setterPropertyMap")
    public void handlesPropertiesWithoutSetters() throws IntrospectionException {
      // Create mock PropertyDescriptor list with one missing setter
      PropertyDescriptor prop1 = new PropertyDescriptor("id", TestInterface.class, "getId", "setId");
      PropertyDescriptor prop2 = new PropertyDescriptor("name", TestInterface.class, "getName", null);
      List<PropertyDescriptor> props = List.of(prop1, prop2);

      Map<Method, Integer> map = Columnar.setterPropertyMap(props);

      assertEquals(1, map.size());
      assertEquals(0, map.get(prop1.getWriteMethod()));
      assertFalse(map.containsKey(null)); // Ensure null is not added as a key
    }

    @Test
    @DisplayName("Should handle properties with only setters in getterPropertyMap")
    public void handlesPropertiesWithOnlySetters() throws IntrospectionException {
      // Create mock PropertyDescriptor list with one missing getter
      PropertyDescriptor prop1 = new PropertyDescriptor("id", OnlySetter.class, null, "setId");
      List<PropertyDescriptor> props = List.of(prop1);

      Map<Method, Integer> map = Columnar.getterPropertyMap(props);

      assertEquals(0, map.size());
      assertFalse(map.containsKey(null)); // Ensure null is not added as a key
    }


    public interface PersonInterface {
      int getId();
      void setId(int id);
      long getSalary();
      void setSalary(long salary);
    }

    public interface ExtendedInterface extends PersonInterface {
      void doSomething();
    }

    @Test
    @DisplayName("Should handle both getters and setters correctly")
    public void handlesGettersAndSettersCorrectly() {
      List<PersonInterface> list = Columnar.proxyList(5, PersonInterface.class);

      PersonInterface person = list.get(0);

      // Test initial values
      assertEquals(0, person.getId());
      assertEquals(0L, person.getSalary());

      // Test setter methods
      person.setId(42);
      person.setSalary(5_000L);

      // Verify values were set
      assertEquals(42, person.getId());
      assertEquals(5_000L, person.getSalary());

      // Verify that the values are also set for direct access
      PersonInterface samePerson = list.get(0);
      assertEquals(42, samePerson.getId());
      assertEquals(5_000L, samePerson.getSalary());
    }

    @Test
    @DisplayName("Should throw UnsupportedOperationException for non-property methods")
    public void throwsForNonPropertyMethods() throws Exception {
      List<ExtendedInterface> list = Columnar.proxyList(1, ExtendedInterface.class);
      ExtendedInterface person = list.get(0);

      // Non-property method should throw
      assertThrows(UnsupportedOperationException.class, person::doSomething);
    }

    @Test
    @DisplayName("Should preserve values across multiple proxies")
    public void preservesValuesAcrossMultipleProxies() {
      List<PersonInterface> list = Columnar.proxyList(5, PersonInterface.class);

      // Set values on first proxy
      PersonInterface alice = list.get(0);
      alice.setId(100);
      alice.setSalary(1200L);

      // Set values on second proxy
      PersonInterface bob = list.get(1);
      bob.setId(200);
      bob.setSalary(800L);

      // Verify values are preserved
      assertEquals(100, list.get(0).getId());
      assertEquals(1200L, list.get(0).getSalary());
      assertEquals(200, list.get(1).getId());
      assertEquals(800L, list.get(1).getSalary());

      // Original proxies should still reflect the same values
      assertEquals(100, alice.getId());
      assertEquals(1200L, alice.getSalary());
      assertEquals(200, bob.getId());
      assertEquals(800L, bob.getSalary());
    }
  }
}
