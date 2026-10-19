# Columnar

Source : [Examen de Java Inside - 2024 - session 2](https://monge.univ-eiffel.fr/ens/IR/IR2/2024-2025/JavaInside/columnar.php).

## Exercice 1 - Columnar

Le but de ce TP est d'implanter des listes (`java.util.List`) de types primitifs (ou records et Java beans contenant des uniquement des types primitifs) en utilisant des tableaux en colonne.

En effet, il est dès fois plus efficace lorsque l'on a des objets comme des personnes qui ont un `id` et un `salary` (salaire) de représenter ceux-ci nom pas comme des lignes, mais comme des colonnes, car on évite les problèmes d'alignements.

Par exemple, si on a trois personnes, 

```text
      id  salary
      --  ------
      12    1000
      14    1250
      17    2045
```

 en mémoire, on peut les représenter par deux tableaux, un tableau d'int (`int[]`) avec les valeurs 12, 14 et 17 et un tableau de long (`long[]`) avec les valeurs 1000, 1250 et 2045 plutôt que comme un tableau de records ayant les composants (`id` et `salary`).

Cette technique est très utilisé dans les jeus vidéos sous le nom struct of array (SOA) et dans la base de données relationnelles (sous le nom de bases orientées colonnes).

Voici trois exemples d'utilisation des méthodes de la classe `Columnar` 

```java
      // Example with a list of primitive
      List<Integer> intList = Columnar.primitiveList(5, int.class);
      for (int i = 0; i < intList.size(); i++) {
        intList.set(i, i * 10);
      }
      System.out.println("Liste d'entiers: " + intList);
```

 

```java
      // Example with a record
      public record Person(int age, long salary) {}
      List<Person> personList = Columnar.recordList(3, Person.class);
      personList.set(0, new Person(28, 60_000L));
      personList.set(1, new Person(35, 75_000L));
      personList.set(2, new Person(42, 90_000L));
      for (Person person : personList) {
        System.out.println(person.age() + " " + person.salary());
      }
```

 

```java
      // Example with an interface containing getters and setters
      public interface Employee {
        int getAge();
        void setAge(int age);
        long getSalary();
        void setSalary(long salary);
      }

      List<Employee> employeeList = Columnar.proxyList(3, Employee.class);

      Employee john = employeeList.get(0);
      john.setAge(31);
      john.setSalary(65000.0);
      Employee ana = employeeList.get(1);
      ana.setAge(27);
      ana.setSalary(58000.0);

      for (int i = 0; i < employeeList.size(); i++) {
        Employee employee = employeeList.get(i);
        System.out.printf(employee.getAge() + " " + employee.getSalary());
      }
```

Dans la suite du TP, nous allons implanter la classe `Columnar` au fur et à mesure.

La javadoc 23 est [https://igm.univ-mlv.fr/~juge/javadoc-23/](https://igm.univ-mlv.fr/~juge/javadoc-23/).

Les trucs et astuces utilisés pour l'implantation [COMPANION.pdf](../minijunit/COMPANION.pdf)

La classe `Utils` qui gère les exceptions correctement est la suivante [Utils.java](Utils.java)

Les tests unitaires correspondant à l'examen se trouvent dans la classe [ColumnarTest.java](../../../../test/java/framework/columnar/ColumnarTest.java)

Note : comme on utilise les tests unitaires JUnit sans Maven, dans la configuration de votre projet, il faut ajouter la librairie JUnit 5, soit à partir du fichier `ColumnarTest.java`, en cliquant sur l'annotation `@Test` et en sélectionnant le quickfix "Fixup project ...", soit en sélectionnant les "Properties" du projet (avec le bouton droit de la souris sur le projet) puis en ajoutant la librairie JUnit 5 (jupiter) au ClassPath.

### Question 1

On souhaite dans un premier temps écrire la méthode `primitiveList` qui prend une taille et une classe représentant un type primitif et renvoie une liste dont tous les éléments sont stockés dans un tableau de ce primitif.

La liste a une taille fixe donnée à la création et ne doit pas permettre de stocker `null`

Vérifier que les tests marqués "Q1" passent.

Rappel: il existe la classe `java.lang.reflect.Array` qui permet de créer (`newInstance(type, length)`) un tableau et accéder (`Array.get()/Array.set()`) à ses éléments quelque soit le type de tableau.

Rappel de Java Avancé: il existe une classe `java.util.AbstractList` qui permet d'implanter facilement une liste.

### Question 2

Dans le but de pouvoir gérer des records composés de type primitif, on va dans un premier temps écrire une méthode d'aide (*helper method*), capable de renvoyer le constructeur canonique d'un record.

Écrire la méthode `findCanonicalConstructor` qui pour une classe correspondant à un record, renvoie le constructeur (`java.lang.reflect.Constructor`) canonique de ce record.

Vérifier que les tests marqués "Q2" passent.

Rappel: il existe une méthode `getRecordComponents()` sur la classe `java.lang.Class`.

Rappel de Java Avancé: le constructeur canonique est celui qui prend en paramètre les valeurs de tous les composants dans le bon ordre.

### Question 3

Toujours dans le but de pouvoir gérer des records composés de type primitif, on va écrire une autre méthode d'aide capable de renvoyer la liste des accesseurs (une liste de `java.lang.reflect.Method`tt>) pour une classe `record`

Écrire la méthode `accessors` qui pour une classe correspondant à un record, renvoie une liste des accesseurs de celui-ci (dans le même ordre que les composants du record).

Vérifier que les tests marqués "Q3" passent.

### Question 4

On peut maintenant écrire la méthode `recordList` qui prend en paramètre une taille et la classe d'un record et renvoie un liste. En interne, la liste stocke pour chaque composant du record (qui doit être un type primitif) un tableau de ce type primitif. Lorsque l'on demande un élément de la liste, on va aller créer un tableau d'objets avec les valeurs de chaque liste de primitif puis appeler le constructeur canonique pour créer le record.

Comme cela, on stocke les valeurs dans des tableaux de primitif compact en mémoire et on ne cré l'instance du record que si l'on en a besoin.

Attention: si vous avez l'intention de stocker les records dans une liste, CE N'EST PAS CE QUI EST DEMANDÉ !

Écrire la méthode `recordList` sachant que pour l'instant, on ne s'intéresse pas à comment faire un `set` dans la liste.

Vérifier que les tests marqués "Q4" passent.

Note: on vous fournit dans `Utils` une méthode `constructorNewInstance` qui permet d'appeler un constructeur avec un tableau des arguments.

Note2: vous avez déjà écrit du code à la question 1, vous avez le droit de vous en re-servir !

### Question 5

Avant, d'implanter la méthode `set` de la liste, nous avons besoin d'une nouvelle méthode d'aide `accessorInvoke` qui premnd en paramètre une méthode (`java.lang.reflect.Method`) et une instance et qui renvoie la valeur de l'accesseur pour cette instance.

Écrire la méthode `accessorInvoke(method, instance)`.

Vérifier que les tests marqués "Q5" passent.

### Question 6

On souhaite maintenant que la méthode `set` marche sur la liste renvoyée par la méthode `recordList`.

Faites les modifications qui s'imposent.

Vérifier que les tests marqués "Q6" passent.

### Question 7

Avant d'implanter la méthode `proxyList`, on va créer une méthode d'aide `getterPropertyMap` qui prend en paramètre une liste de `PropertyDescriptor` et renvoie une `Map` qui associe à chaque accesseur l'entier correspondant à l'index de la propriété dans la liste prise en paramètre.

Par exemple, avec deux propriétés `id` et `name` passée en paramètre dans cet ordre, la méthode `getId()` doit avoir l'index 0 et la méthode `getName` doit avoir l'index 1.

Écrire la méthode d'aide `getterPropertyMap(properties)`.

Vérifier que les tests marqués "Q7" passent.

### Question 8

On souhaite écrire la méthode `proxyList` qui prend une taille et une instance de `java.lang.Class` correspondant à une instance définissant des propriétés suivant la norme Java beans et qui renvoie une liste de proxy créé à la demande (lorsque l'on en a besoin, pas à l'appel de `proxyList`).

Pour l'instant, les proxy ne doivent implanter que les accesseurs permettant d'obtenir les valeurs (donc les getters, pas les setters).

Écrire la méthode `proxyList`.

Vérifier que les tests marqués "Q8" passent.

Note: il existe une méthode `getBeanInfo()` dans `Utils`.

### Question 9

Enfin, écrire une méthode d'aide `setterPropertyMap` qui fonctionne comme `getterPropertyMap` mais pour les setters puis modifier le code de la méthode `proxyList` pour ajouter le support des setters.

Vérifier que les tests marqués "Q9" passent.

## Exécution dans ce projet

Les sources et les tests utilisent le package `framework.columnar`, sans dépendance supplémentaire.

```sh
mvn -o -Dtest=ColumnarTest test
```

La correction repose sur `AbstractList`, `java.lang.reflect.Array`, les composants des records et l’introspection JavaBeans. Les listes ont une taille fixe et le stockage n’accepte que des types primitifs (hors `void`). `primitiveList` interdit `null` et renvoie la valeur précédente lors d’un `set`.

`recordList` conserve un tableau de primitifs par composant. Chaque lecture reconstruit un record à partir des valeurs d’une ligne ; `set` remplace ces valeurs et renvoie le record précédent.

`proxyList` conserve un tableau de primitifs par propriété. Chaque lecture crée un proxy qui accède à la ligne demandée : plusieurs proxies d’une même ligne partagent donc les mêmes valeurs. Les getters et setters sont pris en charge ; les autres méthodes, dont celles de `Object`, lèvent `UnsupportedOperationException`.
