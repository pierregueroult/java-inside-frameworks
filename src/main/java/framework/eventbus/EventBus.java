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

  /*
   * RAPPEL DE COURS — Q8 : ClassValue et durée de vie des métadonnées
   * ClassValue<V> associe une valeur V à chaque Class<?> consultée. get(type)
   * renvoie la valeur associée ou déclenche computeValue(type) si elle manque.
   * Ici : Class<?> -> liste des Method validées, partagée entre tous les bus.
   * Une exception dans computeValue n'est pas mise en cache : un get ultérieur
   * peut retenter le calcul. remove(type) permet de demander un recalcul.
   * Aucune invalidation n'est nécessaire ici : inscrire/fermer un abonné ne
   * change pas les méthodes déclarées dans sa classe.
   * Un champ static appartient à la classe EventBus ; un champ d'instance
   * comme registrations appartient à chaque objet EventBus.
   * Le cache ne doit jamais capturer un listener : ce serait mélanger les
   * métadonnées partagées avec l'état d'une inscription et retenir des objets.
   * Une HashMap<Class<?>, V> statique conserve fortement ses clés. ClassValue
   * est conçu pour associer des métadonnées sans empêcher le déchargement
   * des classes devenues inutilisées. Les valeurs partagées doivent rester
   * non modifiables pour qu'un utilisateur ne corrompe pas le cache commun.
   */
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

  /*
   * RAPPEL DE COURS — Q9 : ThreadLocal, portée dynamique et finally
   * ThreadLocal<T> fournit une case T PAR THREAD : get lit sa case, set la
   * remplace, remove la retire. Un ThreadLocal ordinaire ne transmet pas sa
   * valeur à un autre thread. static ne signifie donc pas « une seule valeur
   * pour tous les threads » : seul l'objet ThreadLocal est partagé.
   * Le contexte est accessible pendant un appel sans être transmis à toutes
   * les méthodes : c'est une portée dynamique, liée à la pile d'appels.
   * Pattern : lire l'ancien contexte -> installer le nouveau -> try/finally.
   * Dans finally : restaurer l'ancien contexte si l'appel est imbriqué ;
   * sinon remove, pour libérer les références au bus et à l'événement.
   * finally s'exécute lors d'un retour ou d'une exception Java, contrairement
   * à une instruction simplement placée après le try.
   * Cela évite aussi qu'un thread réutilisé par un pool garde un ancien contexte.
   * ThreadLocal n'est ni un verrou ni un moyen de rendre ArrayList thread-safe.
   */
  // Q9 : un contexte différent pour chaque thread, partagé entre les bus de ce thread.
  // Le contexte doit être retiré/restauré à la fin de CHAQUE publication.
  private static final ThreadLocal<DispatchContext> CURRENT_DISPATCH = new ThreadLocal<>();

  /*
   * RAPPEL DE COURS — Q1 : Class, Method, annotations et comparateurs
   * Un objet est une instance ; sa Class décrit son type à l'exécution.
   * String.class et "abc".getClass() désignent ainsi la même Class.
   * Class<?> décrit une classe dont on ne connaît pas ici le paramètre générique.
   * getMethods() : méthodes publiques, héritées comprises, sans ordre garanti.
   * getDeclaredMethods() : méthodes déclarées dans cette classe, même privées,
   * mais pas les méthodes uniquement héritées. Les découvrir n'autorise pas
   * pour autant à les appeler : les règles d'accès de Java restent applicables.
   * Une Method décrit un nom, des paramètres, un retour et des modificateurs.
   * getParameterTypes() renvoie des Class, pas les valeurs passées à l'appel.
   * void.class est le type de retour « aucun résultat » ; Void.class est une
   * classe de référence différente. int.class est primitif, Integer.class non.
   * isAnnotationPresent(A.class) teste une annotation disponible à l'exécution.
   * getAnnotation(A.class) renvoie son instance ou null ; getAnnotations()
   * renvoie les annotations visibles sur cet élément.
   * comparing extrait le premier critère ; thenComparing ne départage que les
   * égalités. Ici on trie par nom de méthode, puis nom du type du paramètre.
   */
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

  /*
   * RAPPEL DE COURS — Q2/Q3 : appeler par réflexion et distinguer les erreurs
   * method.invoke(receiver, args...) exécute une méthode sur receiver et renvoie
   * Object. Un résultat primitif est boxé ; un retour void est représenté par null.
   * InvocationTargetException signifie que la méthode appelée a lancé un
   * Throwable : getCause() permet de récupérer l'objet original. Cela vaut
   * pour les exceptions vérifiées, RuntimeException et Error de l'abonné.
   * IllegalAccessException signale au contraire un problème d'accès réfléchi.
   * IllegalArgumentException peut signaler un mauvais receiver ou argument ;
   * notre validation et notre filtrage doivent éviter cette erreur de programmation.
   * Throwable est la racine : Exception comprend les exceptions vérifiées et
   * RuntimeException ; Error est une autre branche. Un catch(Exception) seul
   * ne capture donc pas directement une Error.
   * Ici on collecte les causes des InvocationTargetException pour continuer
   * les autres appels. On ne transforme pas les erreurs du framework en échecs
   * d'abonné : le companion convertit l'accès interdit en IllegalAccessError.
   *
   * RAPPEL DE COURS — Q4 : égalité de types et compatibilité
   * type == event.getClass() impose exactement le même type.
   * type.isInstance(event) accepte aussi les sous-types : CharSequence accepte String.
   * Pour un event non nul, type.isAssignableFrom(event.getClass()) est équivalent.
   * Le sens est « le type de GAUCHE accepte le type de DROITE », pas l'inverse.
   * isInstance(null) vaut false ; event.getClass() sur null provoque une NPE.
   * Un switch avec case String ne sait tester qu'un type écrit dans le code,
   * contrairement au type découvert dynamiquement pour chaque abonné.
   *
   * RAPPEL DE COURS — Q6 : streams, tri stable et instantané
   * filter garde des éléments ; map transforme un élément en un autre ; flatMap
   * transforme chaque élément en un stream puis réunit ces streams en un seul.
   * Ces opérations intermédiaires sont paresseuses : une opération terminale
   * comme toList déclenche le parcours. Stream.toList renvoie une liste non modifiable.
   * Un stream conserve ici l'ordre des List sources. sorted est stable sur
   * un stream ordonné : les priorités égales conservent l'ordre d'inscription,
   * puis l'ordre des méthodes établi en Q1.
   * comparingInt compare des int sans boxing ; reversed inverse l'ordre.
   * Ne pas soustraire deux priorités : le calcul peut déborder aux valeurs extrêmes.
   * Construire calls avant toute invocation fixe les destinataires de CET appel.
   * Les changements d'inscriptions pendant l'exécution affectent les posts suivants.
   */
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

  /*
   * RAPPEL DE COURS — Q2 : valider avant de modifier l'état
   * Une inscription comprend un objet ET les méthodes à appeler sur lui.
   * Une Method peut être partagée entre instances ; son receiver doit rester
   * propre à chaque inscription. Terminer les validations avant registrations.add
   * évite une inscription partielle si une méthode est invalide.
   * Ce principe d'atomicité fonctionnelle ne constitue pas une garantie de concurrence.
   */
  /** Q2, Q5, Q8 : valider, inscrire et renvoyer une fermeture propre à cette inscription. */
  public Subscription register(Object listener) {
    Objects.requireNonNull(listener);
    // Q8 : get réutilise les métadonnées de cette classe, y compris entre plusieurs bus.
    // Q2 : si leur validation échoue, rien n'a encore été ajouté aux inscriptions.
    var registration = new Registration(listener, METHODS.get(listener.getClass()));
    return addRegistration(registration);
  }

  /*
   * RAPPEL DE COURS — Q5 : interface fonctionnelle, capture et identité
   * Une interface fonctionnelle possède une seule méthode abstraite : une lambda
   * fournit son implantation. Ici () -> ... fournit Subscription.close().
   * Une lambda capture les variables locales finales ou effectivement finales
   * (jamais réaffectées). Elle capture la référence, pas une copie de l'objet.
   * == compare l'identité des objets ; equals compare selon leur contrat.
   * Deux records Registration ayant les mêmes composants peuvent être égaux
   * sans être la même inscription. removeIf avec == retire exactement la bonne.
   * Idempotent signifie qu'un second close a le même effet final que le premier.
   * Attention : une lambda sans capture comme () -> {} peut être réutilisée
   * par la JVM ; sa création ne garantit pas une nouvelle identité à chaque appel.
   */
  // Q5, Q12 : le même mécanisme gère aussi les abonnements génériques.
  private Subscription addRegistration(Registration registration) {
    registrations.add(registration);
    // Q5 : capture de registration, qui est effectivement finale.
    // Les records ont un equals structurel : == distingue deux inscriptions identiques.
    // Une deuxième fermeture ne trouve plus l'objet et reste sans effet (idempotence).
    return () -> registrations.removeIf(current -> current == registration);
  }

  /*
   * RAPPEL DE COURS — Q10 : Constructor et règles d'accès
   * getConstructor(types...) cherche un constructeur PUBLIC dont les paramètres
   * correspondent exactement aux Class fournies. Sans argument : constructeur vide.
   * getDeclaredConstructor inclut les constructeurs non publics mais ne rend pas
   * ces constructeurs accessibles. Nous n'utilisons pas setAccessible.
   * Un constructeur public d'une classe inaccessible peut rester non invocable.
   * Constructor<T>.newInstance(args...) crée une instance et renvoie T ; avec
   * Constructor<?> le type concret est inconnu, mais le résultat est utilisable en Object.
   * Absence de constructeur -> NoSuchMethodException à la recherche.
   * Classe abstraite -> InstantiationException à l'instanciation.
   * Accès interdit -> IllegalAccessException.
   * Exception du corps du constructeur -> InvocationTargetException et sa cause.
   * La conversion en NoSuchMethodError/InstantiationError/IllegalAccessError
   * est la convention choisie dans le companion, pas une obligation du langage.
   * initCause conserve l'exception technique originale pour le diagnostic.
   * Une construction réussie ne doit avoir lieu qu'une fois PAR inscription.
   */
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

  /*
   * RAPPEL DE COURS — Q11 : introspection d'un record
   * Déclarer un record et analyser sa structure par réflexion sont deux notions.
   * Class.isRecord() distingue les records des classes ordinaires.
   * getRecordComponents() donne leurs composants dans l'ordre de déclaration ;
   * cette méthode renvoie null si la classe n'est pas un record.
   * Un RecordComponent donne getName(), getType() et getAccessor() (une Method).
   * Pour record Point(int x, int y), les accesseurs sont x() et y(), pas getX/getY.
   * Un accesseur explicite peut calculer sa valeur ou lever une exception : lire
   * directement le champ ne respecterait pas ce comportement.
   * invoquer x() via Method.invoke renvoie un Integer, par boxing du résultat int.
   * Le constructeur canonique prend les composants dans ce même ordre ; le
   * retrouver serait possible avec leurs getType(), mais Q11 ne construit rien.
   * Collectors.joining séparateur/préfixe/suffixe assemble les chaînes du stream.
   * String.valueOf(Object) gère null et utilise le toString de la valeur sinon.
   * Ici on n'appelle pas le toString du record externe ; les valeurs composantes
   * sont formatées normalement, sans sérialisation récursive ni copie des objets.
   */
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

  /*
   * RAPPEL DE COURS — Q10/Q11 : propager une cause et pattern switch
   * Un pattern case RuntimeException exception teste le type et introduit une
   * variable déjà typée, sans cast manuel. Le switch expression produit une valeur.
   * Le cas Error lance directement l'erreur ; les autres cas produisent une
   * RuntimeException que l'appelant lance avec throw uncheckedCause(...).
   * UndeclaredThrowableException permet d'envelopper une exception vérifiée
   * dans une exception non vérifiée en conservant sa cause.
   * Cette politique diffère de Q3 : post collecte les échecs, alors que la
   * construction ou la description échoue en propageant la cause.
   */
  // Q10, Q11 : un switch sur les types connus d'exceptions est approprié ici.
  // Contrairement au type dynamique du paramètre abonné (Q4), ces cas sont fixes.
  private static RuntimeException uncheckedCause(Throwable cause) {
    return switch (cause) {
      case RuntimeException exception -> exception;
      case Error error -> throw error;
      default -> new UndeclaredThrowableException(cause);
    };
  }

  /*
   * RAPPEL DE COURS — Q12 : Type, ParameterizedType et effacement
   * Class décrit une classe à l'exécution. Type est plus général et représente
   * aussi les signatures génériques enregistrées dans le fichier .class.
   * Pour class Audit implements Listener<String> :
   *   getInterfaces()        -> la Class brute Listener.class ;
   *   getGenericInterfaces() -> un Type décrivant Listener<String> ;
   *   getRawType()           -> Listener.class sur ce ParameterizedType ;
   *   getActualTypeArguments()[0] -> String.class.
   *
   * Les principales formes de Type à connaître dans le companion :
   * - Class : String, CharSequence ou un tableau concret comme String[].
   * - ParameterizedType : List<String>, Listener<String>, etc.
   * - TypeVariable : T dans class Generic<T> implements Listener<T>.
   * - GenericArrayType : T[] quand le composant n'est pas une Class concrète.
   * - WildcardType : ? extends Number ou ? super Integer dans une signature.
   * Ne pas caster arbitrairement un Type en Class : tester sa forme auparavant.
   * Le motif argument instanceof Class<?> eventType fait ce test et ce cast.
   *
   * Effacement : E n'a pas de borne explicite, donc onEvent(E) devient
   * onEvent(Object) dans la signature JVM de l'interface. Le compilateur peut
   * générer une méthode pont (bridge) pour déléguer vers onEvent(String).
   * La Class de l'instance ne révèle pas toute instanciation générique :
   * new Generic<String>() et new Generic<Integer>() ont la même classe Generic.
   * À l'inverse, new Listener<String>() { ... } déclare une classe anonyme dont
   * la signature générique conserve ici String. Une lambda n'offre pas cette
   * signature d'implémentation exploitable par notre résolution.
   *
   * Listener<?> signifie « type d'événement inconnu », pas Listener<Object>.
   * On ne peut donc pas appeler librement listener.onEvent(event) en Java typé.
   * Nous résolvons E, vérifions sa compatibilité, puis invoquons la méthode
   * publique effacée de Listener par réflexion.
   * Le TP ne résout que les implémentations DIRECTES avec E représenté par une
   * Class. Remonter une hiérarchie générique complète demanderait de substituer
   * les variables de type à chaque niveau : c'est explicitement hors périmètre.
   */
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

  /*
   * RAPPEL DE COURS — Q7 : proxies dynamiques et méthode générique
   * Proxy.newProxyInstance charge une classe générée qui implémente les interfaces
   * demandées. Il faut leur ClassLoader, un tableau de Class d'interfaces et un handler.
   * Chaque appel passe par InvocationHandler.invoke(proxy, method, args) :
   * proxy = l'objet généré ; method = la méthode d'interface ; args = ses arguments.
   * args peut être null pour une méthode sans argument, notamment celles d'Object.
   * equals/hashCode/toString sont aussi redirigées : les traiter avant args[0].
   * Ici les autres méthodes ont déjà été validées avec exactement un paramètre.
   * Ne pas appeler method.invoke(proxy, args) dans le handler : cela rappelle
   * le même proxy et provoquerait une récursion sans fin.
   * Le handler capture this et appelle son post : il voit donc l'état ACTUEL du bus.
   *
   * <E> E createEmitter(Class<E> type) relie le paramètre et le résultat :
   * passer Orders.class permet au compilateur de déduire le retour Orders.
   * Class.cast vérifie le cast à l'exécution et évite un cast générique non contrôlé.
   * Proxy.isProxyClass(obj.getClass()) permet de reconnaître un proxy JDK ;
   * Proxy.getInvocationHandler(obj) permet ensuite de récupérer son handler.
   * Cette inspection n'est pas nécessaire pour implémenter notre émetteur.
   */
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

  /*
   * RAPPEL DE COURS — Q1 : déclaration et méta-annotations
   * @interface déclare une annotation, pas une classe à instancier avec new.
   * Ses attributs sont des méthodes sans argument ; default fournit une valeur
   * lorsqu'on ne précise pas cet attribut à l'utilisation de l'annotation.
   * @Retention précise sa conservation : SOURCE, CLASS (valeur par défaut)
   * ou RUNTIME. Sans RUNTIME, notre réflexion ne peut pas la lire.
   * @Target limite les éléments autorisés : METHOD ici, TYPE pour une classe,
   * CONSTRUCTOR pour un constructeur, RECORD_COMPONENT pour un composant, etc.
   * Retention et Target sont elles-mêmes des annotations : des méta-annotations.
   */
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

  /*
   * RAPPEL DE COURS — Q2/Q3 : records, constructeur compact et immuabilité
   * Un record génère des champs privés finaux, les accesseurs portant les noms
   * des composants, le constructeur canonique, equals, hashCode et toString.
   * Son equals compare les composants : l'identité de l'objet reste une autre notion.
   * Un constructeur compact n'écrit pas la liste des paramètres. Il peut valider
   * ou réaffecter ces paramètres avant leur affectation automatique aux champs.
   * Ainsi failures = List.copyOf(failures) change la liste réellement conservée.
   * Un champ final interdit sa réaffectation, pas la mutation de l'objet référencé.
   * List.copyOf protège de la mutation ultérieure de la liste fournie et refuse
   * les éléments null. Elle peut réutiliser une liste déjà adaptée : aucune
   * nouvelle identité de liste n'est garantie. Ses éléments ne sont pas clonés.
   * Collections.unmodifiableList crée au contraire une vue non modifiable dont
   * le contenu peut encore changer si la liste source est modifiée ailleurs.
   */
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
