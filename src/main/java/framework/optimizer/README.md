# Optimizer

Source : [Examen de Java Inside - 2024 - session 1](https://monge.univ-eiffel.fr/ens/IR/IR2/2024-2025/JavaInside/optimizer.php).

## Exercice 1 - Optimizer

Le but de ce TP est d'implanter un optimiseur automatique de code qui regarde l'utilisation d'une *library*, ici l'interface `java.util.List` et essaye de choisir la bonne implantation dynamiquement en fonction de quelles méthodes ont été appelées dans le passé (`Profile`) et de recettes (`Recipe`) qui indiquent quelle implantation choisir en fonction d'un profil.

Voici un exemple avancé d'utilisation de la classe `Optimizer` 

```java
public class Demo {
  public static void main(String[] args) {
    var optimizer = new Optimizer(10);
    optimizer.addRecipe(profile -> {
      if (profile.asMap().containsKey("Iterator.remove")) {
        return Optional.of(LinkedList::new);
      }
      return Optional.empty();
    });

    for(var i = 0; i < 10; i++) {
      var list = optimizer.track(new ArrayList<>(List.of(1, 2)));
      for(var iterator = list.iterator(); iterator.hasNext();) {
        iterator.next();
        iterator.remove();
      }
    }
  }
}
```

 Dans un premier temps, on crée un `Optimizer` en indiquant que l'on va établir un profil pour enregistrer les 10 (le paramètre de `Optimizer`) premières méthodes appelées.

Puis au lieu de juste créer une liste avec `new ArrayList<>(List.of(1, 2))`, on ajoute un appel à la méthode `Optimizer.track()` avec la liste en paramètre. Cela veut dire que l'optimiseur va enregistrer (établir un profil) des méthodes qui sont appelées sur l'interface. Dans notre exemple, les méthodes suivantes sont appelées: 

```java
      Demo.main([String)V iterator
      Demo.main([String)V hasNext
      Demo.main([String)V next
      Demo.main([String)V remove
      Demo.main([String)V hasNext
      Demo.main([String)V next
      Demo.main([String)V remove
      Demo.main([String)V hasNext
      Demo.main([String)V iterator
      Demo.main([String)V hasNext\
```

 Le profil compte le nombre de méthodes appelées, donc le profil est 

```java
     List.iterator     2
     Iterator.hasNext  4
     Iterator.next     2
     Iterator.remove   2
```

 Le profil compte les méthodes qui sont appelées à partir de la méthode `main` de la classe `Demo` et contient les méthodes de l'interface `List`, ici `iterator` mais aussi récursivement, les méthodes de l'itérateur lui-même, ici, `hasNext`, `next` et `remove`.

Une fois les 10 méthodes appelées, l'optimiseur va sélectionner une nouvelle implantation en fonction du profil en appelant les recettes (`Recipe`) dans l'ordre dans lequel elles ont été enregistrées. Dans notre exemple, il n'y a qu'une seule recette qui indique d'utiliser `LinkedList` si le profil contient `Iterator.remove`. Donc à partir de ce moment-là, le prochain appel à la méthode `track` va renvoyer une `LinkedList` initialisée avec la liste prise en argument de la méthode `track`.

La complexité pire cas d'un appel à `Iterator.remove()` passe de O(n) sur une `ArrayList` à O(1) sur une `LinkedList` (qui est une liste doublement chaînée).

Pour implanter notre API d'optimisation, nous allons créer une classe `Optimizer` dans laquelle nous ajouterons les méthodes nécessaires. 

```java
public final class Optimizer {
  private static final StackWalker STACK_WALKER =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  public record FrameId(String className, String methodName, String descriptor) {
    public FrameId {
      Objects.requireNonNull(className);
      Objects.requireNonNull(methodName);
      Objects.requireNonNull(descriptor);
    }

    @Override
    public String toString() {
      return className + "." + methodName + descriptor;
    }
  }

  private static FrameId findFrameId(int depth) {
    var frame = STACK_WALKER.walk(s -> s.skip(depth).findFirst())
        .orElseThrow(() -> new IllegalStateException("unknown caller stack frame"));
    return new FrameId(frame.getDeclaringClass().getSimpleName(), frame.getMethodName(), frame.getDescriptor());
  }

  public static FrameId currentFrameId() {
    return findFrameId(2);
  }

  public final static class Profile {
    private final HashMap<String, Long> methodCallMap = new HashMap<>();

    void add(String interfaceName, String methodName) {
      Objects.requireNonNull(interfaceName);
      Objects.requireNonNull(methodName);
      methodCallMap.merge(interfaceName + "." + methodName, 1L, Long::sum);
    }

    public Map<String, Long> asMap() {
      return Collections.unmodifiableMap(methodCallMap);
    }
  }

  private final HashMap<FrameId, Profile> profileMap = new HashMap<>();

  // TODO
}
```

 On ne veut pas que l'optimiseur enregistre un seul profil, mais qu'il enregistre un profil par méthode qui a appelé la méthode `optimizer.track()`. Pour cela, il faut que l'on ait un objet qui représente quelle méthode a appelé la méthode `track()`. On utilise le record `FrameId` pour cela.

La méthode `currentFrameId()` renvoie l'objet `FrameId` de la méthode qui a appelé `currentFrameId()`. Le code de `findFrameId` utilise un `StackWalker` qui permet de remonter le stacktrace jusqu'à une certaine profondeur. Comme `currentFrameId()` appel `findFrameId()`, la profondeur de recherche est ici 2 (on doit sauter `findFrameId`tt> et `currentFrameId()`).

La classe `Profile` contient une table de hachage qui, pour une méthode de l'interface, compte le nombre de fois où celle-ci est appelée.

Dans la suite du TP, nous allons implanter la classe `Optimizer` au fur et à mesure.

La javadoc 23 est [https://igm.univ-mlv.fr/~juge/javadoc-23/](https://igm.univ-mlv.fr/~juge/javadoc-23/).

Les trucs et astuces utilisés pour l'implantation [COMPANION.pdf](../minijunit/COMPANION.pdf)

La classe `Utils` qui gère les exceptions correctement est la suivante [Utils.java](Utils.java)

Les tests unitaires correspondant à l'examen se trouvent dans la classe [OptimizerTest.java](../../../../test/java/framework/optimizer/OptimizerTest.java)

Note : comme on utilise les tests unitaires JUnit sans Maven, dans la configuration de votre projet, il faut ajouter la librairie JUnit 5, soit à partir du fichier `OptimizerTest.java`, en cliquant sur l'annotation `@Test` et en sélectionnant le quickfix "Fixup project ...", soit en sélectionnant les "Properties" du projet (avec le bouton droit de la souris sur le projet) puis en ajoutant la librairie JUnit 5 (jupiter) au ClassPath.

### Question 1

On souhaite dans un premier temps écrire la méthode `track` qui prend une liste en paramètre et renvoie un `Proxy` de cette liste.

Le proxy doit lever l'exception `UnsupportedOperationException` pour les 3 méthodes publiques de `Object` : `equals`, `hashCode` et `toString`.

Pour toutes les autres méthodes, le proxy doit appeler la méthode sur la liste prise en paramètre de la méthode `track()`.

Dans la classe `Optimizer`, écrire la méthode `track` comme décrit ci-dessus.

Vérifier que les tests marqués "Q1" passent.

Note : attention à gérer les exceptions venant du code de la liste correctement.

### Question 2

On souhaite maintenant pouvoir ajouter optionnellement à la construction une fonction qui va *logger* le fonctionnement du proxy. Lorsqu'une méthode du proxy est appelé, le logger *logger* recevoir une chaîne de caractère contenant le `FrameId`, correspondant à la méthode ayant appelé la méthode `track()`, et le nom de la méthode du proxy appelée.

Par exemple, le code suivant affiche "Foo.bar()V add" car la méthode "add" du proxy a été appelé sur un proxy créé (en appelant `track`) dans la classe "Foo", dans la méthode "bar" avec le descriptor "()V" (la signature de la méthode). 

```java
public class Foo {
  public void bar() {
    var optimizer = new Optimizer(System.out::println);
    var list = optimizer.track(new ArrayList<>());
    list.add(3);  // Foo.bar()V add
  }
}
```

Modifier le code de la méthode `track` en conséquence.

Vérifier que les tests marqués "Q2" passent, et que les tests précédents continuent de passer.

Note : `FrameId` à déjà un `toString()` !

### Question 3

On souhaite maintenant introduire une méthode `profile(frameId)` qui prend en paramètre un `FrameId` et renvoie l'objet `Profile` correspondant avec le nombre de méthodes appelées correctement calculé.

Voici un exemple d'utilisation 

```java
     var optimizer = new Optimizer();
     var list = optimizer.track(new ArrayList<String>());
     list.add("foo");
     list.add("bar");
     var profile = optimizer.profile(Optimizer.currentFrameId());
     System.out.printnl(profile.asMap());  // {List.add=2}
```

En terme d'implantation, pour une instance de `FrameId`, on va toujours associer la même instance de `Profile`, et ce pour toute la durée de vie de l'`Optimizer`.

Modifier le code de la méthode `track` afin que, pour un `FrameId`, le profil soit mis à jour en ajoutant la méthode qui vient d'être appelée. Puis ajouter la méthode `profile(frameId)`.

Vérifier que les tests marqués "Q3" passent.

### Question 4

On souhaite maintenant ajouter une méthode `setFactory(frameId, factory)` qui permet, pour un `FrameId`, de spécifier une `Factory`, une fonction qui prend une `List` en paramètre et renvoie une `List`. Si une `Factory` est spécifiée, la méthode `track` va appeler la méthode `apply` de la `Factory` avec le `FrameId` courant, au lieu de créer un proxy.

Il ne doit pas être possible d'assigner une `factory` à un `FrameId` si une `Factory` à déjà été assignée.

Par exemple, avec le code 

```java
     var optimizer = new Optimizer();
     optimizer.setFactory(Optimizer.currentFrameId(), ArrayList::new);
     var list = optimizer.track(List.of(1, 2));
```

 La `list` renvoyée est créée en appelant `new ArrayList<>(List.of(1, 2))`.

Dans un premier temps, déclarer l'interface `Factory` dans `Optimizer`.

Puis écrire la méthode `setFactory(frameId, factory)` et modifier `track()` pour avoir le comportement attendu.

Vérifier que les tests marqués "Q4" passent.

### Question 5

On veut maintenant détecter (pour l'instant *logger*) lorsque les méthodes d'un proxy ont été appelées plus de fois qu'une certaine valeur de seuil (`threshold`). Pour cela, on va ajouter la possibilité d'indiquer un seuil à la construction. Si le nombre de méthodes appelées pour un `FrameId` est égale à ce seuil, alors le proxy va logger le message "optimize" suivi par un espace suivi du `FrameId`.

Par exemple, avec le code 

```java
class Foo {
  void bar() {
    var optimizer = new Optimizer(System.out::println, 2);
    var list = optimizer.track(new ArrayList<>());
    list.add("hello");
    list.add("john);
  }
}
```

 l'affichage est le suivant 

```java
      Foo.bar()V add
      Foo.bar()V add
      optimize Foo.bar()V
```

Modifier le code de la classe `Optimizer` en conséquence.

Vérifier que les tests marqués "Q5" passent.

### Question 6

On souhaite maintenant ajouter une nouvelle méthode `addRecipe(recipe)` qui ajoute une recette (`Recipe`) qui est une fonction qui prend en paramètre un `Profile` et renvoie optionnellement une `Factory`.

Une recette est la fonction qui va être appelée lorsque le seuil (`threshold`) sera atteint pour demander, pour un profil, s'il n'existe pas une `factory` qui peut être installée dans le but de ne plus utiliser le proxy et d'optimiser le code en utilisant la bonne implantation de `List`.

Par exemple, avec le code suivant 

```java
     var optimizer = new Optimizer(2);
     optimizer.addRecipe(profile -> {
       if (profile.asMap().containsKey("List.add")) {
         return Optional.of(ArrayList::new);
       }
       return Optional. empty();
     });

     for(var i = 0; i < 3; i++) {
         var list = optimizer.track(new LinkedList<>());
         list.add(i);
     }
```

 Les deux premières `List` renvoyées par `track` sons des proxies devant une `LinkedList` et le troisième appel renvoie une `ArrayList`.

Dans le cas où il y a plusieurs recettes, celle-ci sont appliquées dans l'ordre d'appel à `addRecipe`, et l'on s'arrête dès qu'une des recettes a renvoyé une `factory`. Dans le cas où aucune `Factory` n'est renvoyée, il ne se passe rien.

Modifier votre code en conséquence.

Vérifier que les tests marqués "Q6" passent.

### Question 7

On voudrait aussi pouvoir *tracker* les méthodes renvoyées par l'itérateur. Pour cela, on va aussi renvoyer un proxy dans le cas où l'on appelle la méthode `iterator()` sur le proxy de le liste.

Modifier votre code pour être capable non seulement de *tracker* les méthodes de la liste, mais aussi les méthodes de l'itérateur.

Vérifier que les tests marqués "Q7" passent.

### Question 8

Enfin, on peut se rendre compte que *tracker* les méthodes de la liste et de l'itérateur n'est pas suffisant, car il y a plein d'autres méthodes qui peuvent renvoyer des objets à *tracker* comme `listIterator()`, `subList`, etc...

Essayer de proposer avec un mécanisme général pour *tracker* toutes les méthodes nécessaires.

Vérifier que les tests marqués "Q8" passent.

## Exécution dans ce projet

Les sources et les tests utilisent le package `framework.optimizer`, sans dépendance supplémentaire.

```sh
mvn -o -Dtest=OptimizerTest test
```

La correction utilise `StackWalker` pour identifier la méthode appelante et des proxies dynamiques pour suivre les appels. Les objets renvoyés sous forme d’interface sont suivis récursivement, avec le même profil. Le profil enregistre les premiers appels jusqu’au seuil (100 par défaut), puis les recettes sont évaluées dans leur ordre d’ajout. La première recette applicable installe la fabrique utilisée par les prochains appels à `track()` de cette méthode.
