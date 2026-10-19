# Mini JUnit

Source : [Examen de Java Inside - 2025 - session 2](https://monge.univ-eiffel.fr/ens/IR/IR2/2025-2026/JavaInside/mini-junit.php).

## Exercice 1 - Mini JUnit

Le but de ce TP est d'implanter une classe `TestRunner` qui implante une version minimale de JUnit.

Voici un exemple d'utilisation de la classe `TestRunner`, dans un premier temps, nous avons besoin d'une classe qui contient les tests.

Un test est une méthode d'instance publique annotée par l'annotation @Test 

```java
public static class ManyMethods {
  @TestRunner.Test public void alpha() { /* passes */ }
  @TestRunner.Test public void beta()  { throw new IllegalStateException(); }
  @TestRunner.Test public void gamma() { /* passes */ }
}
```

On peut noter que les annotations sont déclarées dans la classe `TestRunner`.

Dans l'exemple ci-dessus, il y a trois tests, les tests `alpha` et `gamma` vont être en succès, car ils ne lèvent pas d'exception tandis que `beta` est en failure, car le test lance l'exception `IllegalStateException`.

On lance les tests et l'on récupère les résultats comme ceci: 

```java
    TestRunner.PlanResult planResult = TestRunner.runTest(ManyMethods.class);
    Map<String, TestRunner.TestResult> resultMap = planResult.results();
    IO.println(resultMap.get("alpha"));  // Success[]
    IO.println(resultMap.get("beta"));   // Failure[throwable=java.lang.IllegalStateException]
    IO.println(resultMap.get("gamma"));  // Success[]
```

Les résultats de chaque test sont stockés dans une `java.util.Map` qui associe au nom de la méthode le résultat (`Success` ou `Failure`). Les noms des méthodes dans la `Map` sont triés par ordre alphabétique.

Dans la suite du TP, nous allons implanter la classe `TestRunner` au fur et à mesure, voici une ébauche

```java
public final class TestRunner {
  // TODO: add the annotations here

  public sealed interface TestResult {}
  public record Failure(Throwable throwable) implements TestResult {}
  public record Success() implements TestResult {}
  public record PlanResult(Map<String, TestResult> results) { }

  record UnitTest(Method method) {}
  record TestPlan(Class<?> testClass, List<UnitTest> tests) { }

  static TestPlan plan(Class<?> testClass) {
    // TODO
  }

  public static PlanResult runTest(Class<?> testClass) {
    // TODO
  }
}
```

La classe à un seul point d'entrée, la méthode `runTest(testClass)`, qui crée une instance de la classe `testClass` et exécute les tests sur cette instance.

Le code marche en deux temps, dans un premier temps, on regarde la classe `testClass` et on établit un plan de test en utilisant la méthode `plan` puis on execute celui-ci.

La méthode `plan` ainsi que les classes `UnitTest` et `TestPlan` devraient être privées, mais ont la visibilité de package pour pouvoir être testé par les tests unitaires. Donc attention si vous devez les modifier à faire des modifications `backward-compatible`, sinon les tests précédents ne vont plus fonctionner.

La javadoc 25 est [https://igm.univ-mlv.fr/~juge/javadoc-25/](https://igm.univ-mlv.fr/~juge/javadoc-25/).

Les trucs et astuces utilisés pour l'implantation [COMPANION.pdf](COMPANION.pdf)

La classe `Utils` qui gère les exceptions correctement : [Utils.java](Utils.java)

Les tests unitaires correspondant à l'examen se trouvent dans la classe [TestRunnerTest.java](../../../../test/java/framework/minijunit/TestRunnerTest.java)

Note : comme on utilise les tests unitaires JUnit sans Maven, dans la configuration de votre projet, il faut ajouter la librairie JUnit 5, soit à partir du fichier `TestRunnerTest.java`, en cliquant sur l'annotation `@Test` et en sélectionnant le quickfix "Fixup project ...", soit en sélectionnant les "Properties" du projet (avec le bouton droit de la souris sur le projet) puis en ajoutant la librairie JUnit 5 (jupiter) au ClassPath.

### Question 1

On cherche dans un premier temps à implanter les méthodes `plan` et `runTest` telles que l'on puisse exécuter les tests. Pour l'instant, on supposera que tous les tests marchent.

En utilisant la classe `Utils.java`, écrire la méthode `plan` qui cherche à l'intérieur de la classe de test, les méthodes publiques annotées par l'annotation `@Test` et renvoie un plan d'exécution de ces méthodes.

Ensuite, implanter la méthode `runTest` qui appel la méthode `plan` puis exécute les tests du plan sur une instance de la classe de test (toujours en utilisant `Utils.java`).

Vérifier que les tests unitaires marqués "Q1" passent.

### Question 2

On souhaite maintenant que le code marche si un des tests lève une exception, n'importe laquelle (en récupérant un `Throwable` donc). Bien sûr, lorsque qu'un test plante, il faut continuer à éxécuter les tests suivants.

Modifier votre code en conséquence.

Vérifier que les tests unitaires marqués "Q2" passent.

### Question 3

On souhaite que la `Map` des résultats organise les noms des méthodes dans l'ordre lexicographique, si vous ne l'avez pas déjà fait, modifier votre implantation pour que cela soit le cas.

Vérifier que les tests unitaires marqués "Q3" passent.

### Question 4

On veut ajouter la possibilité d'annoter une méthode de la classe de test avec les annotations `@Before` ou `@After`.

Une méthode annotée avec `@Before` doit être exécutée avant **chaque** test. Une méthode annotée avec `@After` doit être exécutée après **chaque** test.

Lors de l'exécution, si une méthode `@Before` plante avec une exception, le test doit être en "failure", avec l'exception associée. Même chose si une méthode `@After` plante.

Si le test plante, il faut quand même exécuter la méthode `@After` car si `@Before` a alloué des ressources, comme des fichiers temporaires, il faut les fermer en exécutant le `@After`.

Une exception doit être levée si plusieurs méthodes de la classe de test possède une annotation `@Before` (respectivement `@After`).

Vérifier que les tests unitaires marqués "Q4" passent.

### Question 5

Dans le cas où le test plante **et** la méthode `@After` plante aussi, l'exception levée par la méthode `@After` doit être ajoutée comme exception `suppressed` (aller voir la javadoc de `Throwable` si vous ne vous rappelez ce que c'est) à l'exception levée par la méthode testée.

Vérifier que les tests unitaires marqués "Q5" passent.

### Question 6

Enfin, à l'intérieur d'une classe de test, on veut pouvoir regrouper plusieurs tests ensembles dans une classe interne (`inner classe`) si celle-ci est annotée avec l'annotation `@Nested`.

Lorsque l'on exécute un test dans une classe `@Nested` avec JUnit, celui-ci exécute les méthodes `@Before` et `@After` des classes englobantes d'abord. Ici, on va faire plus simple, on va exécuter uniquement les méthodes `@Before` et `@After` de la classe dans laquelle se trouve chaque test.

Rappel du cours de Java: lorsque l'on instancie une `inner-class`, on doit envoyer une instance de la classe englobante en paramètre.

Vérifier que les tests unitaires marqués "Q6" passent.

## Exécution dans ce projet

Les classes et les tests utilisent le package `framework.minijunit`. Les dépendances JUnit du projet suffisent.

```sh
mvn -o -Dtest=TestRunnerTest test
```

Les résultats sont triés par nom ; les tests des classes englobantes s’exécutent avant ceux des classes `@Nested`. Conformément aux tests fournis, `@After` n’est pas appelé lorsque `@Before` échoue.
