# TP noté blanc — Mini EventBus

Sujet original d’entraînement, inspiré du niveau des examens Java Inside présents dans ce projet. Ce sujet n’est pas un examen officiel.

**Durée conseillée : 2 h 30. Barème : 20 points.**

Le but est d’implanter un bus d’événements. Des objets abonnés déclarent des méthodes annotées `@EventBus.Subscribe`. Lorsqu’un événement est publié, le bus appelle les méthodes compatibles et renvoie un bilan de leur exécution.

Les fichiers de cet exercice sont [EventBus.java](EventBus.java) et les [tests EventBusTest.java](../../../../test/java/framework/eventbus/EventBusTest.java). Initialement laissé vide pour l’entraînement, `EventBus.java` contient désormais la correction commentée des questions Q1 à Q12 et sert de fiche de révision autonome.

## Conditions

- Utiliser uniquement le JDK et les dépendances déjà présentes dans le projet. JUnit sert aux tests.
- Ne pas modifier les tests, ni les solutions des autres packages.
- Déduire et écrire les signatures à partir des consignes. Respecter les noms, types et visibilités demandés pour être compatible avec les tests. L’organisation interne est libre.
- Ne pas utiliser `setAccessible`, `trySetAccessible`, ni accéder directement aux champs des abonnés.
- L’exercice est mono-thread : aucune gestion des accès concurrents n’est demandée.
- Les documents `COMPANION.md`, `JDBC.md` et la documentation du JDK sont autorisés. Aucune base de données n’est nécessaire ici.
- Pendant l’entraînement, ne pas consulter les corrections des autres TP. Les indices seront donnés seulement sur demande.

## API et exemple

```java
public record OrderCreated(long id) {}

public static class Audit {
  @EventBus.Subscribe
  public void onOrder(OrderCreated event) {
    System.out.println("Commande " + event.id());
  }
}

var bus = new EventBus();
var subscription = bus.register(new Audit());
var report = bus.post(new OrderCreated(42));
// report.attempted() == 1
// report.failures().isEmpty()
subscription.close();
// Les prochains événements ne sont plus transmis à cet abonnement.
```

Un même objet bus peut gérer plusieurs types d’événements. Les événements ne sont jamais conservés pour être rejoués ultérieurement.

## Question 1 — Découvrir les méthodes abonnées (3 points)

Déclarer dans `EventBus` une annotation publique nommée `Subscribe`, visible à l’exécution et applicable uniquement aux méthodes. Elle possède un attribut `priority` de type `int`, dont la valeur par défaut est zéro.

Écrire une méthode statique `subscriberMethods`, de visibilité de package, prenant une classe quelconque et renvoyant une liste de `java.lang.reflect.Method`.

Une méthode abonnée est une méthode publique d’instance portant `@Subscribe`. Les méthodes héritées sont prises en compte. Les méthodes privées, les méthodes statiques et les méthodes non annotées sont ignorées.

Une méthode abonnée doit renvoyer `void` et prendre exactement un paramètre de type référence. Un paramètre primitif est interdit. Une méthode publique d’instance annotée qui ne respecte pas ces règles provoque `IllegalArgumentException`.

Le résultat est ordonné par nom de méthode, puis, en cas de surcharge, par nom complet du type de son paramètre (`Class.getName()`). Un type nul provoque `NullPointerException`. Une classe sans méthode abonnée donne une liste vide.

**Tests : Q1.**

## Question 2 — Inscrire et publier (3 points)

Permettre la création d’un bus par un constructeur public sans argument.

Déclarer dans `EventBus` les types publics suivants :

- Un record `Failure`, avec les composants `listener` de type `Object`, `method` de type `java.lang.reflect.Method` et `cause` de type `Throwable`, dans cet ordre. Il sera utilisé en Q3.
- Un record `DispatchReport`, avec les composants `attempted` de type `int` et `failures` de type `List<Failure>`, dans cet ordre.
- Une interface `Subscription` qui étend `AutoCloseable` et redéclare `close` sans argument, sans valeur de retour et sans exception vérifiée.

Écrire deux méthodes publiques d’instance : `register`, prenant un objet abonné et renvoyant un `Subscription`, et `post`, prenant un événement de type `Object` et renvoyant un `DispatchReport`.

Implanter ces méthodes pour les cas où toutes les méthodes abonnées réussissent. Pour cette question, seuls les paramètres dont le type est exactement celui de l’événement doivent être reconnus.

Chaque appel à `register` crée un abonnement indépendant, même si l’objet a déjà été inscrit. L’inscription ne déclenche aucune méthode. Les méthodes d’un abonnement sont appelées dans l’ordre défini en Q1. Les abonnements sont parcourus dans leur ordre d’inscription.

`post` renvoie un `DispatchReport`. Son champ `attempted` est le nombre total de méthodes effectivement appelées pour cet événement. Son champ `failures` est une liste vide lorsque toutes les méthodes réussissent. Un événement sans destinataire donne un bilan à zéro appel.

`register` renvoie un objet non nul de type `Subscription`. Son action `close` peut rester sans effet jusqu’à la Q5.

Un abonné nul ou un événement nul provoque `NullPointerException`. Un objet sans méthode abonnée peut être inscrit. Si l’une des méthodes d’un objet est invalide, `register` échoue sans inscrire aucune méthode de cet objet.

**Tests : Q2.**

## Question 3 — Isoler les échecs (3 points)

Un abonné peut lancer n’importe quel `Throwable`, y compris une exception vérifiée ou une `Error`. La publication doit continuer vers les autres destinataires.

Chaque échec ajoute un `Failure` au bilan, contenant l’objet abonné exact, la méthode appelée et le `Throwable` original lancé par l’abonné. Ne pas stocker l’enveloppe de réflexion à la place de cette exception.

Les échecs apparaissent dans l’ordre d’appel. `attempted` compte les succès et les échecs. Les exceptions d’un abonné ne sont pas propagées par `post`.

La liste `failures` d’un bilan doit être non modifiable. Un bilan déjà retourné ne doit pas changer lors d’une publication ultérieure.

**Tests : Q3.**

## Question 4 — Événements polymorphes (3 points)

Étendre la compatibilité : une méthode reçoit l’événement si son paramètre peut accepter cet objet suivant les règles de typage de Java.

Ainsi, un événement de type `String` peut être reçu par une méthode prenant `String`, `CharSequence` ou `Object`, mais pas par une méthode prenant `Integer`. Si plusieurs méthodes d’un même abonnement sont compatibles, chacune est appelée une fois. Il ne faut pas privilégier les types les plus spécifiques : les règles d’ordre restent les mêmes.

Le `Throwable` éventuellement lancé n’intervient pas dans la compatibilité.

**Tests : Q4.**

## Question 5 — Fermer un abonnement (3 points)

Implanter `Subscription.close` pour désinscrire uniquement l’abonnement créé par l’appel correspondant à `register`.

La fermeture est idempotente : fermer deux fois le même abonnement ne fait rien de plus et ne lève pas d’exception. Elle n’affecte pas les autres abonnements, même si le même objet a été inscrit plusieurs fois.

Un objet peut être inscrit de nouveau après fermeture. Fermer un abonnement vide doit également fonctionner.

**Tests : Q5.**

## Question 6 — Priorités et publication en cours (3 points)

Prendre en compte `@Subscribe(priority = ...)` : les méthodes ayant une priorité plus élevée sont appelées avant les autres, tous abonnements confondus.

À priorité égale, conserver l’ordre d’inscription, puis l’ordre des méthodes défini en Q1. Toutes les valeurs de `int`, y compris les valeurs extrêmes et négatives, sont autorisées.

La liste des appels à effectuer pour un événement est fixée au début de `post`. Une inscription ou une fermeture effectuée par un abonné pendant cet appel n’a donc d’effet que sur les publications suivantes. Une méthode déjà prévue reste appelée même si son abonnement est fermé entre-temps.

**Tests : Q6.**

## Question 7 — Émetteur typé (2 points)

Écrire une méthode publique d’instance générique `createEmitter`, prenant la classe d’une interface et renvoyant une instance de cette même interface. Le typage doit permettre l’affectation ci-dessous sans cast. Cette méthode doit créer un proxy dynamique de l’interface fournie.

```java
public interface Orders {
  EventBus.DispatchReport created(OrderCreated event);
}

Orders orders = bus.createEmitter(Orders.class);
var report = orders.created(new OrderCreated(42));
```

Chaque méthode d’instance de cette interface doit être abstraite, prendre exactement un paramètre de type référence et renvoyer exactement `EventBus.DispatchReport`. Les méthodes héritées sont également validées. Les méthodes statiques de l’interface sont ignorées. Une interface vide est autorisée.

Une interface invalide, une classe ordinaire ou une interface scellée provoque `IllegalArgumentException` dès `createEmitter`, avant tout appel au proxy. Un type nul provoque `NullPointerException`.

Lorsqu’une méthode d’émission est appelée, l’événement est publié sur le bus ayant créé le proxy et le bilan est renvoyé. Le nom de la méthode n’a pas d’importance. Les règles de `post`, notamment le refus d’un événement nul, s’appliquent.

`equals`, `hashCode` et `toString` du proxy doivent lever `UnsupportedOperationException`. L’émetteur doit observer les inscriptions et fermetures réalisées après sa création.

**Tests : Q7.**

## Question 8 — Mettre en cache les méthodes abonnées avec ClassValue (bonus)

Lorsque plusieurs objets d’une même classe sont inscrits, la découverte, la validation et le tri des méthodes abonnées produisent le même résultat. On souhaite réutiliser ces informations entre les inscriptions, y compris entre plusieurs instances d’`EventBus`.

Ajouter un cache privé, statique et final de type `ClassValue<List<Method>>`. Pour chaque classe, son calcul doit réutiliser la méthode `subscriberMethods` de Q1 et conserver une liste non modifiable. Adapter `register` pour obtenir les méthodes depuis ce cache.

Le cache doit contenir uniquement les informations liées à la classe : il ne doit conserver ni objet abonné, ni inscription, ni événement. Deux objets d’une même classe continuent d’avoir des inscriptions indépendantes. La validation doit toujours avoir lieu avant l’ajout d’une inscription, et les comportements des questions précédentes restent identiques.

Expliquer pourquoi `ClassValue` convient à ce cache et ce qu’il apporte par rapport à une map statique ayant des classes comme clés. Préciser si une invalidation du cache est nécessaire dans le cadre de cet exercice.

Référence autorisée : [ClassValue dans le companion](../../../../../COMPANION.md#classvalue).

**Tests : Q8**, avec le tag `blanc-q8`. Ils vérifient l’indépendance des objets abonnés après utilisation du cache, l’isolation des inscriptions entre bus, la séparation des métadonnées de classes différentes et l’absence d’inscription partielle après des validations invalides répétées. Tous les tests Q1 à Q7 doivent également continuer à passer.

```sh
mvn -o -Dtest=EventBusTest -Dgroups=blanc-q8 test
```

Ces tests vérifient le comportement observable, sans accéder aux champs privés. Ils ne prouvent pas à eux seuls que la découverte des méthodes est mise en cache : l’utilisation effective de `ClassValue` se vérifie aussi par lecture du code. Le barème initial reste sur 20 points ; cette question est un complément d’entraînement.

## Question 9 — Contexte de publication avec ThreadLocal (complément)

On souhaite qu’un abonné puisse consulter le bus et l’événement courants sans recevoir de paramètre supplémentaire.

Déclarer dans `EventBus` un record public `DispatchContext`, dont les composants sont `bus` de type `EventBus` et `event` de type `Object`. Ces composants ne doivent pas être nuls. Ajouter une méthode publique statique `currentDispatch()` qui renvoie le contexte du thread courant, ou lève `IllegalStateException` si aucune publication n’est active.

Pendant les appels aux abonnés, `post` doit installer un contexte contenant le bus qui publie et l’objet événement original. Utiliser un `ThreadLocal` privé, statique et final pour ce contexte. Un autre thread ne doit pas voir ce contexte. Cela n’ajoute pas de garantie d’accès concurrent à un même bus.

Un abonné peut effectuer une publication imbriquée, sur le même bus ou sur un autre bus. À son retour, le contexte de la publication englobante doit être restauré. À la sortie de la publication la plus externe, le contexte doit être retiré, même en cas d’exception. Les erreurs d’abonnés restent collectées selon Q3. Une tentative de publication d’un événement nul ne doit pas modifier un contexte déjà actif.

Exemple d’accès depuis une méthode abonnée :

```java
var context = EventBus.currentDispatch();
System.out.println(context.event());
```

**Tests : Q9**, tag `blanc-q9`. Référence : [ThreadLocal dans le companion](../../../../../COMPANION.md#threadlocal).

## Question 10 — Construire un abonné par réflexion (complément)

Ajouter la méthode publique d’instance `registerClass`, prenant une classe quelconque et renvoyant un `Subscription`.

```java
var subscription = bus.registerClass(Audit.class);
```

Chaque appel doit créer une nouvelle instance en utilisant son constructeur public sans argument, puis l’inscrire. Cette instance est conservée pour les publications suivantes : il ne faut pas créer un objet à chaque événement. Le handle renvoyé ferme uniquement cette inscription, selon Q5.

Les méthodes abonnées doivent être validées avant l’appel au constructeur : une classe ayant des méthodes invalides ne doit donc produire aucun effet de construction ni inscription partielle. Réutiliser le cache de Q8 et l’inscription de Q2/Q5. Aucun accès forcé, aucune injection et aucun argument de constructeur ne sont demandés.

Les erreurs attendues sont les suivantes :

- Classe nulle : `NullPointerException`.
- Absence de constructeur public sans argument : `NoSuchMethodError`, avec la `NoSuchMethodException` originale comme cause.
- Classe abstraite non instanciable : `InstantiationError`, avec l’`InstantiationException` comme cause.
- Accès interdit au constructeur : `IllegalAccessError`, avec l’`IllegalAccessException` comme cause.
- `RuntimeException` ou `Error` lancée par le constructeur : propager l’objet original, sans l’enveloppe `InvocationTargetException`.
- Autre exception lancée par le constructeur : `UndeclaredThrowableException`, contenant l’exception originale comme cause.

Une classe sans méthode abonnée peut être construite et inscrite. Un échec de validation ou de construction ne doit laisser aucun abonnement dans le bus.

**Tests : Q10**, tag `blanc-q10`. Références : [recherche de constructeurs](../../../../../COMPANION.md#classgetmethod-classgetmethods-classgetconstructors) et [invocation par réflexion](../../../../../COMPANION.md#methodinvoke-constructornewinstance).

## Question 11 — Décrire un événement record (complément)

Écrire une méthode publique statique `describeEvent`, prenant un objet et renvoyant une chaîne. Elle prépare le texte d’une entrée de journal sans publier l’événement ni conserver d’historique.

```java
public record OrderCreated(long id, double amount) {}

EventBus.describeEvent(new OrderCreated(42, 19.5));
// OrderCreated[id=42, amount=19.5]
```

L’objet doit être un record. Un objet nul provoque `NullPointerException` et un autre type provoque `IllegalArgumentException`.

Utiliser l’introspection des records pour récupérer leurs composants, dans leur ordre de déclaration, ainsi que leurs noms et accesseurs. Le préfixe est le nom simple de la classe, suivi de crochets. Les composants sont séparés par une virgule et une espace ; chaque composant est représenté par `nom=valeur`. Un record vide donne `Nom[]`.

Les valeurs doivent être obtenues en appelant les accesseurs publics, sans lire directement les champs et sans appeler le `toString()` de l’objet événement. Chaque valeur est formatée avec `String.valueOf` ; un composant nul donne `null`. Ce format n’est ni du JSON, ni une sérialisation récursive : les valeurs composées utilisent leur propre représentation habituelle.

Mettre les métadonnées des composants en cache dans un `ClassValue`. Ne pas mettre en cache les valeurs des événements. Un accesseur personnalisé doit être respecté. Une `RuntimeException` ou une `Error` provenant d’un accesseur est propagée sans perdre son identité ; une autre exception est enveloppée dans `UndeclaredThrowableException`. Un accès interdit provoque `IllegalAccessError` avec sa cause.

**Tests : Q11**, tag `blanc-q11`. Références : [Record](../../../../../COMPANION.md#record) et [ClassValue](../../../../../COMPANION.md#classvalue).

## Question 12 — Inscription générique Listener<E> (complément)

Ajouter dans `EventBus` une interface fonctionnelle publique `Listener<E>`, dont la méthode `onEvent` prend un événement de type `E`, renvoie `void` et peut lancer un `Throwable`.

Ajouter une méthode publique d’instance `registerTyped` prenant un `Listener<?>` et renvoyant un `Subscription`. Elle inscrit uniquement la méthode `onEvent`, sans exiger d’annotation. Déclarer également la méthode statique `listenerEventType`, de visibilité de package, qui prend une classe quelconque et renvoie le type d’événement sous forme de `Class<?>`.

```java
public static class Audit implements EventBus.Listener<String> {
  @Override
  public void onEvent(String event) {
    System.out.println(event);
  }
}

var subscription = bus.registerTyped(new Audit());
bus.post("hello"); // transmis à Audit
bus.post(42);      // ignoré par Audit
subscription.close();
```

Pour cet exercice, la classe de l’abonné doit déclarer **directement** `Listener<E>` parmi ses interfaces. D’autres interfaces peuvent être présentes. Retrouver `E` à partir de la déclaration générique en utilisant `getGenericInterfaces()` et `ParameterizedType`.

L’argument générique doit être représenté par une `Class<?>` connue à l’exécution. Les classes, interfaces et tableaux, comme `String`, `CharSequence` et `String[]`, sont donc acceptés. Un type paramétré comme `List<String>` ou une variable de type non résolue comme `T` sont refusés avec `IllegalArgumentException`.

Le type brut `Listener`, les implémentations héritées uniquement d’une superclasse ou d’une sous-interface et les lambdas ne sont pas pris en charge. Une classe anonyme déclarant explicitement `new Listener<String>() { ... }` est acceptée. Une classe sans déclaration compatible provoque `IllegalArgumentException`. Les arguments nuls de `registerTyped` et `listenerEventType` provoquent `NullPointerException`. Tout échec de résolution doit survenir avant l’inscription.

Le bus doit utiliser `E` comme critère de compatibilité selon Q4. Attention : après effacement, la méthode de l’interface est `onEvent(Object)` ; son paramètre réfléchi ne suffit pas à retrouver le type `E`.

Les abonnements génériques ont une priorité de zéro. Ils partagent avec les abonnements annotés l’ordre d’inscription à priorité égale, la fermeture idempotente, l’instantané des appels, le contexte `ThreadLocal` et la collecte des échecs. Un échec indique comme méthode `Listener.onEvent(Object)`, comme destinataire l’objet abonné original et comme cause le `Throwable` original. Deux inscriptions du même objet restent indépendantes.

`register(Object)` et `registerClass(Class<?>)` conservent leur rôle : ils inscrivent les méthodes annotées. L’inscription générique passe explicitement par `registerTyped`.

**Tests : Q12**, tag `blanc-q12`. Référence : [Java Compiler generics attributes](../../../../../COMPANION.md#java-compiler-generics-attributes).

Les questions Q8 à Q12 sont des compléments de révision ; le barème initial de Q1 à Q7 reste sur 20 points.

## Lancer les tests

Tous les tests du TP sont activés. Pour refaire l’exercice en partant d’une classe vide, commencer par déclarer l’API décrite dans les questions, puis travailler les comportements question par question.

La sélection par question filtre l’exécution, pas la compilation : les signatures des questions suivantes doivent donc aussi exister pour lancer Q1. Leurs corps peuvent rester provisoirement non implémentés.

Toutes les questions :

```sh
mvn -o -Dtest=EventBusTest test
```

Une seule question, par exemple Q1 :

```sh
mvn -o -Dtest=EventBusTest -Dgroups=blanc-q1 test
```

Remplacer `blanc-q1` par `blanc-q2`, etc., jusqu’à `blanc-q12` pour une autre question. Les questions sont cumulatives : les comportements déjà demandés doivent continuer à fonctionner.

La totalité des tests du projet, y compris ce TP blanc :

```sh
mvn -o test
```

Les tests sont un moyen de vérifier la réalisation ; le barème porte également sur le respect des consignes et la lisibilité. Aucun objectif de performance supplémentaire n’est imposé.
