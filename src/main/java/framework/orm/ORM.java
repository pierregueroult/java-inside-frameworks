package framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Stream;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }


  // --- do not change the code above

  private static final ThreadLocal<Connection> CURRENT_CONNECTION = new ThreadLocal<>();

  static Connection currentConnection() {
    var connection = CURRENT_CONNECTION.get();
    if (connection == null) {
      throw new IllegalStateException("no active transaction");
    }
    return connection;
  }

  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);
    var previous = CURRENT_CONNECTION.get();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      CURRENT_CONNECTION.set(connection);
      try {
        block.run();
        connection.commit();
      } catch (SQLException | RuntimeException | Error e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackException) {
          e.addSuppressed(rollbackException);
        }
        if (e instanceof UncheckedSQLException sqlException) {
          throw sqlException.getCause();
        }
        throw e;
      } finally {
        if (previous == null) {
          CURRENT_CONNECTION.remove();
        } else {
          CURRENT_CONNECTION.set(previous);
        }
      }
    }
  }

  static String findTableName(Class<?> beanClass) {
    Objects.requireNonNull(beanClass);
    var annotation = beanClass.getAnnotation(Table.class);
    return annotation == null ? beanClass.getSimpleName().toUpperCase(Locale.ROOT) : annotation.value();
  }

  static String findColumnName(PropertyDescriptor property) {
    Objects.requireNonNull(property);
    var annotation = property.getReadMethod().getAnnotation(Column.class);
    return annotation == null ? property.getName() : annotation.value();
  }

  private static List<PropertyDescriptor> properties(BeanInfo beanInfo) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
        .filter(property -> !property.getName().equals("class"))
        .toList();
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var connection = currentConnection();
    var columns = new StringJoiner(", ", "CREATE TABLE " + findTableName(beanClass) + " (", ");");
    for (var property : properties(Utils.beanInfo(beanClass))) {
      var type = property.getPropertyType();
      var sqlType = TYPE_MAPPING.get(type);
      if (sqlType == null) {
        throw new IllegalArgumentException("unsupported property type " + type.getName());
      }
      var getter = property.getReadMethod();
      var name = findColumnName(property);
      var column = name + " " + sqlType;
      if (getter.isAnnotationPresent(GeneratedValue.class)) {
        column += " AUTO_INCREMENT";
      }
      if (type.isPrimitive()) {
        column += " NOT NULL";
      }
      columns.add(column);
      if (getter.isAnnotationPresent(Id.class)) {
        columns.add("PRIMARY KEY (" + name + ")");
      }
    }
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(columns.toString());
    }
  }

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor)
      throws SQLException {
    var bean = Utils.newInstance(constructor);
    for (var property : properties(beanInfo)) {
      Utils.invokeMethod(bean, property.getWriteMethod(), resultSet.getObject(findColumnName(property)));
    }
    return bean;
  }

  static List<Object> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo,
                              Constructor<?> constructor, Object... args) throws SQLException {
    try (var statement = connection.prepareStatement(sqlQuery)) {
      for (var i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      try (var resultSet = statement.executeQuery()) {
        var result = new ArrayList<Object>();
        while (resultSet.next()) {
          result.add(toEntityClass(resultSet, beanInfo, constructor));
        }
        return result;
      }
    }
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo) {
    var columns = new StringJoiner(", ");
    var parameters = new StringJoiner(", ");
    for (var property : properties(beanInfo)) {
      columns.add(findColumnName(property));
      parameters.add("?");
    }
    // MERGE inserts new rows and updates rows whose primary key already exists.
    var operation = findId(beanInfo) == null ? "INSERT" : "MERGE";
    return operation + " INTO " + tableName + " (" + columns + ") VALUES (" + parameters + ");";
  }

  static Object save(Connection connection, String tableName, BeanInfo beanInfo, Object bean,
                     PropertyDescriptor idProperty) throws SQLException {
    Objects.requireNonNull(bean);
    var generatedId = idProperty != null
        && idProperty.getReadMethod().isAnnotationPresent(GeneratedValue.class)
        && Utils.invokeMethod(bean, idProperty.getReadMethod()) == null;
    try (var statement = connection.prepareStatement(createSaveQuery(tableName, beanInfo),
        Statement.RETURN_GENERATED_KEYS)) {
      var index = 1;
      for (var property : properties(beanInfo)) {
        statement.setObject(index++, Utils.invokeMethod(bean, property.getReadMethod()));
      }
      statement.executeUpdate();
      if (generatedId) {
        try (var keys = statement.getGeneratedKeys()) {
          if (keys.next()) {
            var key = keys.getObject(1, idProperty.getPropertyType());
            Utils.invokeMethod(bean, idProperty.getWriteMethod(), key);
          }
        }
      }
      return bean;
    }
  }

  static PropertyDescriptor findId(BeanInfo beanInfo) {
    return properties(beanInfo).stream()
        .filter(property -> property.getReadMethod().isAnnotationPresent(Id.class))
        .findFirst().orElse(null);
  }

  static PropertyDescriptor findProperty(BeanInfo beanInfo, String name) {
    return properties(beanInfo).stream().filter(property -> property.getName().equals(name))
        .findFirst().orElseThrow(() -> new IllegalStateException("unknown property " + name));
  }

  public static <R> R createRepository(Class<R> repositoryType) {
    Objects.requireNonNull(repositoryType);
    if (!repositoryType.isInterface()) {
      throw new IllegalArgumentException("expected a repository interface");
    }
    var beanType = findBeanTypeFromRepository(repositoryType);
    var beanInfo = Utils.beanInfo(beanType);
    var constructor = Utils.defaultConstructor(beanType);
    var tableName = findTableName(beanType);
    var idProperty = findId(beanInfo);
    return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(),
        new Class<?>[] {repositoryType}, (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            throw new UnsupportedOperationException(method.getName());
          }
          var connection = currentConnection();
          var arguments = args == null ? new Object[0] : args;
          try {
            var query = method.getAnnotation(Query.class);
            if (query != null) {
              var result = findAll(connection, query.value(), beanInfo, constructor, arguments);
              return method.getReturnType() == java.util.Optional.class ? result.stream().findFirst() : result;
            }
            var name = method.getName();
            if (name.equals("findAll") && arguments.length == 0) {
              return findAll(connection, "SELECT * FROM " + tableName, beanInfo, constructor);
            }
            if (name.equals("save") && arguments.length == 1) {
              return save(connection, tableName, beanInfo, arguments[0], idProperty);
            }
            if (name.startsWith("findBy") && name.length() > 6 && arguments.length == 1) {
              var property = name.equals("findById") ? idProperty
                  : findProperty(beanInfo, Introspector.decapitalize(name.substring(6)));
              if (property == null) {
                throw new IllegalStateException("no primary key for " + beanType.getName());
              }
              return findAll(connection, "SELECT * FROM " + tableName + " WHERE "
                  + findColumnName(property) + " = ?", beanInfo, constructor, arguments).stream().findFirst();
            }
            throw new IllegalStateException("unsupported repository method " + method);
          } catch (SQLException e) {
            throw new UncheckedSQLException(e);
          }
        }));
  }
}
