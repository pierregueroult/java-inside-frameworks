# Java Inside — Frameworks et révisions

Un projet Maven unique pour travailler la réflexion Java en construisant de petits frameworks : sérialisation JSON, injection de dépendances, interception, ORM et exercices Java Inside.

Les sources sont organisées par package sous `src/main/java/framework`. Chaque package contient son sujet dans un `README.md` ; les tests sont regroupés sous `src/test/java/framework`.

## Démarrer

Prérequis : **JDK 27** et **Maven 3.9 ou supérieur**.

```sh
mvn test
```

Une fois les dépendances téléchargées, les tests peuvent être exécutés hors ligne :

```sh
mvn -o test
```

## Exercices

| Package | Sujet | Contenu |
| --- | --- | --- |
| `framework.mapper` | [Écriture JSON](src/main/java/framework/mapper/README.md) | Correction et tests : primitives, beans, records et annotations |
| `framework.injector` | [Injection de dépendances](src/main/java/framework/injector/README.md) | Correction et tests : providers, constructeurs et setters |
| `framework.interceptor` | [Interception](src/main/java/framework/interceptor/README.md) | Correction et tests : advices, proxies, chaînage et cache |
| `framework.orm` | [ORM](src/main/java/framework/orm/README.md) | Correction et tests : transactions JDBC, tables et repositories |
| `framework.minijunit` | [Mini JUnit](src/main/java/framework/minijunit/README.md) | Correction et tests : plan d’exécution, cycle de vie et classes imbriquées |
| `framework.optimizer` | [Optimizer](src/main/java/framework/optimizer/README.md) | Correction et tests : profils d’appels et choix d’implémentation |
| `framework.columnar` | [Columnar](src/main/java/framework/columnar/README.md) | Correction et tests : stockage par colonnes de primitifs |
| `framework.eventbus` | [Mini EventBus](src/main/java/framework/eventbus/README.md) | TP blanc original, correction commentée et tests Q1 à Q12 |

Les parties [lecture JSON](src/main/java/framework/mapper/README2.md) et [scan d’annotations](src/main/java/framework/injector/README2.md) sont conservées pour un travail ultérieur. Leurs implémentations restent incomplètes et leurs tests d’exercice restent commentés.

## Réviser avec EventBus

[EventBus.java](src/main/java/framework/eventbus/EventBus.java) est une fiche de révision autonome : les commentaires relient chaque partie du code à sa question et expliquent les points importants.

| Questions | Notions |
| --- | --- |
| Q1 à Q3 | Annotations, réflexion, invocation et gestion des exceptions |
| Q4 à Q6 | Polymorphisme, abonnements, identité, priorités et tri stable |
| Q7 et Q8 | Proxies dynamiques et cache `ClassValue` |
| Q9 et Q10 | Contexte `ThreadLocal` et construction par réflexion |
| Q11 et Q12 | Introspection des records, `ParameterizedType` et effacement des génériques |

Tous les tests EventBus :

```sh
mvn -Dtest=EventBusTest test
```

Une question, par exemple Q12 :

```sh
mvn -Dtest=EventBusTest -Dgroups=blanc-q12 test
```

Les tags vont de `blanc-q1` à `blanc-q12`. La sélection filtre l’exécution, pas la compilation des tests.

## Documents et sources

- [COMPANION.md](COMPANION.md) : notions et astuces de réflexion Java.
- [JDBC.md](JDBC.md) : connexions, transactions et requêtes SQL.
- [SOURCES.md](SOURCES.md) : provenance des sujets, squelettes et tests.
- [LICENSE](LICENSE) : licence Apache 2.0 conservée du dépôt source de Rémi Forax.

Ce dépôt est un support personnel d’entraînement, pas une publication officielle de l’Université Gustave Eiffel.
