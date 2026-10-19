package framework.eventbus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fiche de révision autonome : mini bus d'événements, sans bibliothèque externe.
 *
 * Q1 : annotations, découverte et validation des méthodes par réflexion.
 * Q2 : inscriptions, records et invocation d'une méthode sur son objet.
 * Q3 : exceptions de réflexion et bilans immuables.
 * Q4 : compatibilité des types (interfaces et héritage).
 * Q5 : fermeture d'un abonnement, capture et identité des objets.
 * Q6 : priorité, tri stable et instantané des appels avant exécution.
 * Q7 : proxy dynamique, InvocationHandler et méthode générique.
 * Q8 : cache de métadonnées par classe avec ClassValue.
 * Q9 : contexte par thread avec ThreadLocal, finally et appels imbriqués.
 * Q10 : recherche et invocation d'un constructeur public sans argument.
 * Q11 : introspection des composants d'un record pour décrire un événement.
 * Q12 : type générique d'un Listener<E>, ParameterizedType et effacement.
 *
 * Usage :
 *   var bus = new EventBus();
 *   var subscription = bus.register(listener);
 *   var report = bus.post("bonjour");
 *   subscription.close();
 *
 * Le bus est mono-thread : ThreadLocal isole le contexte, mais ne rend pas
 * la liste des inscriptions utilisable simultanément par plusieurs threads.
 */
public final class EventBus {
  // Q2 : état propre à chaque bus, contrairement aux métadonnées statiques de Q8.
  private final List<Registration> registrations = new ArrayList<>();

  // Q8 : seule la classe détermine ses méthodes, jamais l'instance abonnée.
  // ClassValue permet le déchargement des classes, contrairement à une map
  // statique ordinaire gardant ses clés Class en références fortes.
  private static final ClassValue<List<Method>> METHODS = new ClassValue<>() {
    @Override
    protected List<Method> computeValue(Class<?> type) {
      return subscriberMethods(type);
    }
  };

  // Q11 : l'ordre des composants est celui de la déclaration du record.
  // On cache la structure, jamais les valeurs propres à un événement.
  private static final ClassValue<List<RecordComponent>> RECORD_COMPONENTS = new ClassValue<>() {
    @Override
    protected List<RecordComponent> computeValue(Class<?> type) {
      if (!type.isRecord()) {
        throw new IllegalArgumentException("Not a record: " + type.getName());
      }
      return Arrays.stream(type.getRecordComponents()).toList();
    }
  };

  // Q12 : la méthode de l'interface générique est effacée en onEvent(Object).
  // Son paramètre réfléchi ne suffit donc pas à retrouver E.
  private static final Method LISTENER_METHOD = listenerMethod();

  // Q9 : un contexte différent pour chaque thread, partagé entre les bus de ce thread.
  // Le contexte doit être retiré/restauré à la fin de CHAQUE publication.
  private static final ThreadLocal<DispatchContext> CURRENT_DISPATCH = new ThreadLocal<>();

  // Q1 : getMethods() inclut les méthodes publiques héritées.
  // Les méthodes statiques/non annotées sont ignorées AVANT la validation.
  static List<Method> subscriberMethods(Class<?> type) {
    Objects.requireNonNull(type);
    return Arrays.stream(type.getMethods())
        .filter(method -> method.isAnnotationPresent(Subscribe.class))
        .filter(method -> !method.accessFlags().contains(AccessFlag.STATIC))
        .map(EventBus::validateSubscriberMethod)
        .sorted(Comparator.comparing(Method::getName)
            .thenComparing(method -> method.getParameterTypes()[0].getName()))
        .toList(); // Liste non modifiable, partageable entre les inscriptions (Q8).
  }

  private static Method validateSubscriberMethod(Method method) {
    if (method.getReturnType() != void.class) {
      throw new IllegalArgumentException("Subscriber method must return void: " + method);
    }
    var parameters = method.getParameterTypes();
    if (parameters.length != 1) {
      throw new IllegalArgumentException("Subscriber method must have one parameter: " + method);
    }
    if (parameters[0].isPrimitive()) {
      throw new IllegalArgumentException("Subscriber method parameter must not be primitive: " + method);
    }
    return method;
  }

  /** Q2 à Q6 : préparer les appels, puis exécuter en isolant les échecs. Q9 : borner le contexte. */
  public DispatchReport post(Object event) {
    Objects.requireNonNull(event);

    // Q4 : le type du paramètre est dynamique ; isInstance tient compte de l'héritage.
    // Object et CharSequence acceptent par exemple tous deux un événement String.
    // flatMap rassemble les listes de méthodes de toutes les inscriptions.
    var calls = registrations.stream()
        .flatMap(registration -> registration.methods().stream()
            .map(method -> new Call(registration.listener(), method,
                registration.eventType() == null
                    ? method.getParameterTypes()[0] : registration.eventType())))
        // Q12 : pour Listener<E>, le type de filtrage vient de l'attribut générique,
        // pas du paramètre Object de la méthode effacée onEvent(Object).
        .filter(call -> call.eventType().isInstance(event))
        // Q6 : priorité décroissante. Le tri stable garde l'ordre initial en cas d'égalité.
        // comparingInt évite le boxing et les débordements d'une soustraction de priorités.
        .sorted(Comparator.comparingInt(Call::priority).reversed())
        .toList();

    // Q6 : toList() a matérialisé les appels AVANT l'exécution. Un abonné peut donc
    // s'inscrire/se désinscrire pendant un appel sans modifier cet instantané.
    // Q9 : sauvegarder le contexte externe pour autoriser un post dans un autre post.
    var previous = CURRENT_DISPATCH.get();
    CURRENT_DISPATCH.set(new DispatchContext(this, event));
    try {
      var failures = new ArrayList<Failure>();
      // Une boucle rend l'ordre et la gestion des exceptions plus lisibles qu'un stream ici.
      for (var call : calls) {
        try {
          // Q2 : invoke(receiver, arguments...). La méthode appartient à une classe,
          // mais il faut aussi l'objet concret sur lequel exécuter cette méthode.
          call.method().invoke(call.listener(), event);
        } catch (InvocationTargetException e) {
          // Q3 : toute exception de l'abonné (même Error) est enveloppée par la réflexion.
          // getCause() récupère le Throwable ORIGINAL ; on enregistre puis on continue.
          failures.add(new Failure(call.listener(), call.method(), e.getCause()));
        } catch (IllegalAccessException e) {
          // Erreur d'accès de réflexion, distincte d'un échec dans le corps de l'abonné.
          // Convention du companion : convertir en Error en conservant la cause.
          throw (IllegalAccessError) new IllegalAccessError().initCause(e);
        }
      }
      return new DispatchReport(calls.size(), failures);
    } finally {
      // Q9 : finally est exécuté après un return et après une exception.
      if (previous == null) {
        CURRENT_DISPATCH.remove(); // Ne pas conserver le bus et l'événement dans le thread.
      } else {
        CURRENT_DISPATCH.set(previous); // Reprendre le contexte de la publication englobante.
      }
    }
  }

  /** Q9 : accessible aux abonnés, sans leur transmettre un paramètre supplémentaire. */
  public static DispatchContext currentDispatch() {
    var context = CURRENT_DISPATCH.get();
    if (context == null) {
      throw new IllegalStateException("No active dispatch on this thread");
    }
    return context;
  }

  /** Q2, Q5, Q8 : valider, inscrire et renvoyer une fermeture propre à cette inscription. */
  public Subscription register(Object listener) {
    Objects.requireNonNull(listener);
    // Q8 : get réutilise les métadonnées de cette classe, y compris entre plusieurs bus.
    // Q2 : si leur validation échoue, rien n'a encore été ajouté aux inscriptions.
    var registration = new Registration(listener, METHODS.get(listener.getClass()));
    return addRegistration(registration);
  }

  // Q5, Q12 : le même mécanisme gère aussi les abonnements génériques.
  private Subscription addRegistration(Registration registration) {
    registrations.add(registration);
    // Q5 : capture de registration, qui est effectivement finale.
    // Les records ont un equals structurel : == distingue deux inscriptions identiques.
    // Une deuxième fermeture ne trouve plus l'objet et reste sans effet (idempotence).
    return () -> registrations.removeIf(current -> current == registration);
  }

  /** Q10 : construire un nouvel abonné pour cet appel, puis réutiliser register(Object). */
  public Subscription registerClass(Class<?> listenerType) {
    Objects.requireNonNull(listenerType);
    // Valider avant de déclencher les effets éventuels du constructeur.
    METHODS.get(listenerType);
    var constructor = publicDefaultConstructor(listenerType);
    var listener = newListener(constructor);
    return register(listener);
  }

  private static Constructor<?> publicDefaultConstructor(Class<?> type) {
    try {
      // Q10 : getConstructor() cherche le constructeur PUBLIC sans argument.
      // Pas de setAccessible : on respecte les règles d'accès de Java.
      return type.getConstructor();
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError(
          "No public default constructor: " + type.getName()).initCause(e);
    }
  }

  private static Object newListener(Constructor<?> constructor) {
    try {
      return constructor.newInstance();
    } catch (InstantiationException e) {
      // Une classe abstraite peut avoir un constructeur public, mais pas être instanciée.
      throw (InstantiationError) new InstantiationError().initCause(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (InvocationTargetException e) {
      // Q10 : même enveloppe qu'avec Method.invoke, mais l'inscription échoue ici.
      // On propage les exceptions non vérifiées et Error sans perdre leur identité.
      // Une exception vérifiée est enveloppée dans UndeclaredThrowableException.
      throw uncheckedCause(e.getCause());
    }
  }

  /**
   * Q11 : texte d'une entrée de journal, sans appeler le toString du record lui-même.
   * Exemple : describeEvent(new OrderCreated(42, 19.5)) -> OrderCreated[id=42, amount=19.5].
   * Les valeurs sont formatées avec String.valueOf ; ce n'est pas un sérialiseur récursif.
   * Cette méthode ne publie rien et ne conserve aucun historique d'événements.
   */
  public static String describeEvent(Object event) {
    Objects.requireNonNull(event);
    return RECORD_COMPONENTS.get(event.getClass()).stream()
        .map(component -> component.getName() + "="
            + String.valueOf(invokeAccessor(component.getAccessor(), event)))
        .collect(Collectors.joining(", ", event.getClass().getSimpleName() + "[", "]"));
  }

  private static Object invokeAccessor(Method accessor, Object event) {
    try {
      // Q11 : un composant fournit son nom, son type et sa méthode d'accès.
      // L'accès passe par cette méthode publique, jamais directement par le champ.
      return accessor.invoke(event);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    } catch (InvocationTargetException e) {
      throw uncheckedCause(e.getCause());
    }
  }

  // Q10, Q11 : un switch sur les types connus d'exceptions est approprié ici.
  // Contrairement au type dynamique du paramètre abonné (Q4), ces cas sont fixes.
  private static RuntimeException uncheckedCause(Throwable cause) {
    return switch (cause) {
      case RuntimeException exception -> exception;
      case Error error -> throw error;
      default -> new UndeclaredThrowableException(cause);
    };
  }

  /**
   * Q12 : pas d'annotation requise. Le type E est lu sur la classe de l'objet.
   * Exemple : class Audit implements Listener<OrderCreated> { ... }
   * L'ordre, la priorité zéro, le contexte et les échecs réutilisent post().
   */
  public Subscription registerTyped(Listener<?> listener) {
    Objects.requireNonNull(listener);
    var eventType = listenerEventType(listener.getClass());
    return addRegistration(new Registration(listener, List.of(LISTENER_METHOD), eventType));
  }

  // Q12 : getInterfaces() ne fournit que les Class brutes. getGenericInterfaces()
  // fournit des Type, dont des ParameterizedType, qui conservent Listener<String>.
  static Class<?> listenerEventType(Class<?> listenerType) {
    Objects.requireNonNull(listenerType);
    var declaration = Arrays.stream(listenerType.getGenericInterfaces())
        .filter(ParameterizedType.class::isInstance)
        .map(ParameterizedType.class::cast)
        .filter(type -> type.getRawType() == Listener.class)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "Expected a direct implementation of Listener<E>: " + listenerType.getName()));
    var argument = declaration.getActualTypeArguments()[0];
    // E doit ici être une Class concrètement connue (String, CharSequence, String[]...).
    // List<String> est un ParameterizedType et T une TypeVariable : on les refuse.
    if (argument instanceof Class<?> eventType) {
      return eventType;
    }
    throw new IllegalArgumentException("Unsupported event type: " + argument.getTypeName());
  }

  private static Method listenerMethod() {
    try {
      // Q12 : E disparaît de la signature JVM (effacement). On recherche donc Object.
      return Listener.class.getMethod("onEvent", Object.class);
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    }
  }

  @FunctionalInterface
  public interface Listener<E> {
    void onEvent(E event) throws Throwable;
  }

  /** Q7 : E lie le type de la Class reçue au type renvoyé ; aucun cast côté utilisateur. */
  public <E> E createEmitter(Class<E> interfaceType) {
    Objects.requireNonNull(interfaceType);
    if (!interfaceType.isInterface() || interfaceType.isSealed()) {
      throw new IllegalArgumentException("Expected a non-sealed interface: " + interfaceType);
    }
    Arrays.stream(interfaceType.getMethods())
        .filter(method -> !method.accessFlags().contains(AccessFlag.STATIC))
        .forEach(EventBus::validateEmitterMethod);

    // Q7 : cette lambda implante InvocationHandler.invoke(proxy, method, args).
    // Le proxy est l'objet émetteur ; l'appel est redirigé vers le bus capturé (this).
    InvocationHandler handler = (proxy, method, args) -> {
      if (method.getDeclaringClass() == Object.class) {
        throw new UnsupportedOperationException(method.getName());
      }
      return post(args[0]); // Réutilise validation, contexte, priorités et collecte des échecs.
    };
    // La JVM fabrique une classe implémentant l'interface et utilisant ce handler.
    var proxy = Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class<?>[] {interfaceType}, handler);
    return interfaceType.cast(proxy); // Cast vérifié, sans cast générique non contrôlé.
  }

  private static void validateEmitterMethod(Method method) {
    var parameters = method.getParameterTypes();
    if (!method.accessFlags().contains(AccessFlag.ABSTRACT)
        || method.getReturnType() != DispatchReport.class
        || parameters.length != 1
        // || court-circuite : [0] n'est consulté que si la longueur vaut 1.
        || parameters[0].isPrimitive()) {
      throw new IllegalArgumentException("Invalid emitter method: " + method);
    }
  }

  // Q2, Q5 : une seule méthode abstraite permet l'implémentation par lambda.
  // close() redéclaré sans throws évite d'imposer une exception vérifiée à l'appelant.
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  // Q1 : RUNTIME rend l'annotation consultable par réflexion ; METHOD limite sa cible.
  // Un attribut d'annotation est une méthode sans paramètre avec éventuellement un default.
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Subscribe {
    int priority() default 0;
  }

  // Q6 : un appel lie une méthode à son destinataire. Les accesseurs d'un record
  // s'appellent listener() et method(), sans préfixe get.
  public record Call(Object listener, Method method, Class<?> eventType) {
    public Call {
      Objects.requireNonNull(listener);
      Objects.requireNonNull(method);
      Objects.requireNonNull(eventType);
    }

    // Garder le constructeur des questions précédentes.
    public Call(Object listener, Method method) {
      this(listener, method, method.getParameterTypes()[0]);
    }

    int priority() {
      var annotation = method.getAnnotation(Subscribe.class);
      return annotation == null ? 0 : annotation.priority(); // Q12 : sans annotation, priorité zéro.
    }
  }

  // Q3 : le constructeur compact valide/transforme les paramètres avant l'affectation.
  // List.copyOf refuse null et crée un instantané non modifiable ; les éléments
  // (dont les Throwable) ne sont pas clonés : l'immuabilité n'est pas profonde.
  public record DispatchReport(int attempted, List<Failure> failures) {
    public DispatchReport {
      if (attempted < 0) {
        throw new IllegalArgumentException("Cannot have negative attempts: " + attempted);
      }
      failures = List.copyOf(failures);
    }
  }

  // Q2 : l'objet reste propre à l'inscription ; la liste de Method peut venir du cache Q8.
  public record Registration(Object listener, List<Method> methods, Class<?> eventType) {
    public Registration {
      Objects.requireNonNull(listener);
      methods = List.copyOf(methods);
    }

    // Q12 : null signifie « utiliser le paramètre de chaque méthode annotée ».
    // Une inscription générique fournit au contraire son E résolu explicitement.
    public Registration(Object listener, List<Method> methods) {
      this(listener, methods, null);
    }
  }

  // Q3 : conserver les objets originaux, en particulier l'identité de la cause.
  public record Failure(Object listener, Method method, Throwable cause) {
    public Failure {
      Objects.requireNonNull(listener);
      Objects.requireNonNull(method);
      Objects.requireNonNull(cause);
    }
  }

  // Q9 : état temporaire de l'appel, et non état permanent d'un bus ou d'un abonné.
  public record DispatchContext(EventBus bus, Object event) {
    public DispatchContext {
      Objects.requireNonNull(bus);
      Objects.requireNonNull(event);
    }
  }
}
