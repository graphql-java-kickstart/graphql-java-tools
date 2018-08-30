# GraphQL Java Tools

[![TravisCI Build](https://travis-ci.org/graphql-java/graphql-java-tools.svg?branch=master)](https://travis-ci.org/graphql-java/graphql-java-tools)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.graphql-java/graphql-java-tools/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.graphql-java/graphql-java-tools)
[![Chat on Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/graphql-java/graphql-java)

This library allows you to use the GraphQL schema language to build your [graphql-java](https://github.com/graphql-java/graphql-java) schema.
Inspired by [graphql-tools](https://github.com/apollographql/graphql-tools), it parses the given GraphQL schema and allows you to BYOO (bring your own object) to fill in the implementations.
GraphQL Java Tools works extremely well if you already have domain POJOs that hold your data (e.g. for RPC, ORM, REST, etc) by allowing you to map these magically to GraphQL objects.

GraphQL Java Tools aims for seamless integration with Java, but works for any JVM language.  Try it with Kotlin!


## Why GraphQL Java Tools?

* **Schema First**:  GraphQL Java Tools allows you to write your schema in a simple, portable way using the [GraphQL schema language](http://graphql.org/learn/schema/) instead of hard-to-read builders in code.
* **Minimal Boilerplate**:  It takes a lot of work to describe your GraphQL-Java objects manually, and quickly becomes unreadable.
A few libraries exist to ease the boilerplate pain, including [GraphQL-Java's built-in schema-first wiring](http://graphql-java.readthedocs.io/en/latest/schema.html), but none (so far) do type and datafetcher discovery.
* **Stateful Data Fetchers**:  If you're using an IOC container (like Spring), it's hard to wire up datafetchers that make use of beans you've already defined without a bunch of fragile configuration.  GraphQL Java Tools allows you to register "Resolvers" for any type that can bring state along and use that to resolve fields.
* **Generated DataFetchers**:  GraphQL Java Tools automatically creates data fetchers for your fields that call the appropriate method on your java class.  This means all you have to do to create a new field is add the field definition to your schema and add a corresponding method on your class.
* **Type->Class Discovery**:  GraphQL Java Tools starts from your root objects (Query, Mutation) and, as it's generating data fetchers for you, starts to learn about the classes you use for a certain GraphQL type.
* **Class Validation**:  Since there aren't any compile-time checks of the type->class relationship, GraphQL Java Tools will warn you if you provide classes/types that you don't need to, as well as erroring if you use the wrong Java class for a certain GraphQL type when it builds the schema.
* **Unit Testing**:  Since your GraphQL schema is independent of your data model, this makes your classes simple and extremely testable.

## Usage

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**

- [Maven/Gradle](#mavengradle)
- [Examples](#examples)
- [Defining a Schema](#defining-a-schema)
- [Resolvers and Data Classes](#resolvers-and-data-classes)
  - [Root Resolvers](#root-resolvers)
  - [Field Mapping Priority](#field-mapping-priority)
- [Enum Types](#enum-types)
- [Input Objects](#input-objects)
- [Interfaces and Union Types](#interfaces-and-union-types)
- [Scalar Types](#scalar-types)
- [Type Dictionary](#type-dictionary)
- [Making the graphql-java Schema Instance](#making-the-graphql-java-schema-instance)
- [GraphQL Descriptions](#graphql-descriptions)
- [GraphQL Deprecations](#graphql-deprecations)
- [Schema Parser Options](#schema-parser-options)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->


### Maven/Gradle

```xml
<dependency>
    <groupId>com.graphql-java</groupId>
    <artifactId>graphql-java-tools</artifactId>
    <version>5.2.0</version>
</dependency>
```
```groovy
compile 'com.graphql-java:graphql-java-tools:5.2.0'
```

### Examples

A working [Java Spring-Boot application](example) is provided, based off the [Star Wars API](https://github.com/graphql-java/graphql-java/blob/master/src/test/groovy/graphql/StarWarsSchema.java) tests and [test data](https://github.com/graphql-java/graphql-java/blob/master/src/test/groovy/graphql/StarWarsData.groovy).
If you're using Spring Boot, check out the [graphql-spring-boot-starter](https://github.com/graphql-java/graphql-spring-boot)!

A working [Kotlin example](src/test/kotlin/com/coxautodev/graphql/tools/EndToEndSpec.kt) can be found in the tests.

### Defining a Schema

A [GraphQL schema](http://graphql.org/learn/schema/) can be given either as raw strings:

```java
// My application class
SchemaParser.newParser()
    .schemaString("Query { }")
```

or as files on the classpath:

```java
// My application class
SchemaParser.newParser()
    .file("my-schema.graphqls")

// my-schema.graphqls
Query { }
```

Multiple sources will be concatenated together in the order given, allowing you to modularize your schema if desired.


### Resolvers and Data Classes

GraphQL Java Tools maps fields on your GraphQL objects to methods and properties on your java objects.
For most scalar fields, a POJO with fields and/or getter methods is enough to describe the data to GraphQL.
More complex fields (like looking up another object) often need more complex methods with state not provided by the GraphQL context (repositories, connections, etc).
GraphQL Java Tools uses the concept of "Data Classes" and "Resolvers" to account for both of these situations.

Given the following GraphQL schema
```graphql
type Query {
    books: [Book!]
}

type Book {
    id: Int!
    name: String!
    author: Author!
}

type Author {
    id: Int!
    name: String!
}
```

GraphQL Java Tools will expect to be given three classes that map to the GraphQL types: `Query`, `Book`, and `Author`.
The Data classes for Book and Author are simple:

```java
class Book {
    private int id;
    private String name;
    private int authorId;
    
    // constructor
    
    // getId
    // getName
    // getAuthorId
}

class Author {
    private int id;
    private String name;
    
    // constructor
    
    // getId
    // getName
}
```

But what about the complex fields on `Query` and `Book`?
These are handled by "Resolvers".  Resolvers are object instances that reference the "Data Class" they resolve fields for.

The BookResolver might look something like this:
```java
class BookResolver implements GraphQLResolver<Book> /* This class is a resolver for the Book "Data Class" */ {
    
    private AuthorRepository authorRepository;
    
    public BookResolver(AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }
    
    public Author author(Book book) {
        return authorRepository.findById(book.getAuthorId());
    }
}
```

When given a BookResolver instance, GraphQL Java Tools first attempts to map fields to methods on the resolver before mapping them to fields or methods on the data class.
If there is a matching method on the resolver, the data class instance is passed as the first argument to the resolver function.  This does not apply to root resolvers, since those don't have a data class to resolve for.
An optional argument can be defined to inject the `DataFetchingEnvironment`, and must be the last argument.

#### Root Resolvers

Since the Query/Mutation/Subscription objects are root GraphQL objects, they doesn't have an associated data class.  In those cases, any resolvers implementing `GraphQLQueryResolver`, `GraphQLMutationResolver`, or `GraphQLSubscriptionResolver` will be searched for methods that map to fields in their respective root types.  Root resolver methods can be spread between multiple resolvers, but a simple example is below:
```java
class Query implements GraphQLQueryResolver {
    
    private BookRepository bookRepository;
    
    public Query(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }
    
    public List<Book> books() {
        return bookRepository.findAll();
    }
}
```

Resolvers must be provided to the schema parser:
```java
SchemaParser.newParser()
    // ...
    .resolvers(new Query(bookRepository), new BookResolver(authorRepository))
```

#### Field Mapping Priority

The field mapping is done by name against public/protected methods and public/protected/private fields, with the following priority:

First on the resolver or root resolver (note that dataClassInstance doesn't apply for root resolvers):
1. `method <name>(dataClassInstance, *fieldArgs [, DataFetchingEnvironment])`
2. `method is<Name>(dataClassInstance, *fieldArgs [, DataFetchingEnvironment])`, only if the field returns a `Boolean`
3. `method get<Name>(dataClassInstance, *fieldArgs [, DataFetchingEnvironment])`
4. `method getField<Name>(dataClassInstance, *fieldArgs [, DataFetchingEnvironment])`

Then on the data class:
1. `method <name>(*fieldArgs [, DataFetchingEnvironment])`
2. `method is<Name>(*fieldArgs [, DataFetchingEnvironment])`, only if the field returns a `Boolean`
3. `method get<Name>(*fieldArgs [, DataFetchingEnvironment])`
4. `method getField<Name>(*fieldArgs [, DataFetchingEnvironment])`
5. `field <name>`

*Note:* All reflection discovery is done on startup, and runtime reflection method calls use [reflectasm](https://github.com/EsotericSoftware/reflectasm), which increases performance and unifies stacktraces.  No more `InvocationTargetException`!

*Note:* `java.util.Optional` can be used for nullable field arguments and nullable return values, and the schema parser will verify that it's not used with non-null field arguments and return values.

*Note:* Methods on `java.lang.Object` are excluded from method matching, for example a field named `class` will require a method named `getFieldClass` defined.

### Enum Types

Enum values are automatically mapped by `Enum#name()`.

### Input Objects

GraphQL input objects don't need to be provided when parsing the schema - they're inferred from the resolver or data class method at run-time.
If graphql-java passes a `Map<?, ?>` as an argument, GraphQL Java Tools attempts to marshall the data into the class expected by the method in that argument location.

This resolver method's first argument will be marshalled automatically:
```java
class Query extends GraphQLRootResolver {
    public int add(AdditionInput input) {
        return input.getFirst() + input.getSecond();
    }
}

class AdditionInput {
    private int first;
    private int second;
    
    // getFirst()
    // getSecond()
}
```

### Interfaces and Union Types

GraphQL interface/union types are automatically resolved from the schema and the list of provided classes, and require no extra work outside of the schema.
Although not necessary, it's generally a good idea to have java interfaces that correspond to your GraphQL interfaces to keep your code understandable.

### Scalar Types

It's possible to create custom scalar types in GraphQL-Java by creating a new instance of the `GraphQLScalarType` class.  To use a custom scalar with GraphQL Java Tools, add the scalar to your GraphQL schema:
```graphql
scalar UUID
```

Then pass the scalar instance to the parser:
```java
SchemaParser.newParser()
    // ...
    .scalars(myUuidScalar)
```

### Type Dictionary

Sometimes GraphQL Java Tools can't find classes when it scans your objects, usually because of limitations with interface and union types.  Sometimes your Java classes don't line up perfectly with your GraphQL schema, either. GraphQL Java Tools allows you to provide additional classes manually and "rename" them if desired:
```java
SchemaParser.newParser()
    // ...
    .dictionary(Author.class)
    .dictionary("Book", BookClassWithIncorrectName.class)
```

### Making the graphql-java Schema Instance

After you've passed all relavant schema files/class to the parser, call `.build()` and `.makeExecutableSchema()` to get a graphql-java `GraphQLSchema`:

```java
SchemaParser.newParser()
    // ...
    .build()
    .makeExecutableSchema()
```

If you want to build the `GraphQLSchema` yourself, you can get all of the parsed objects with `parseSchemaObjects()`:

```java
SchemaParser.newParser()
    // ...
    .build()
    .parseSchemaObjects()
```

### GraphQL Descriptions

GraphQL object/field/argument descriptions can be provided by comments in the schema:

```graphql
# One of the films in the Star Wars Trilogy
enum Episode {
    # Released in 1977
    NEWHOPE
    # Released in 1980
    EMPIRE
    # Released in 1983
    JEDI
}
```
### GraphQL Deprecations

GraphQL field/enum deprecations can be provided by the `@deprecated(reason: String)` directive, and are added to the generated schema.
You can either supply a **reason** argument with a string value or not supply one and receive a "No longer supported" message when introspected:

```graphql
# One of the films in the Star Wars Trilogy
enum Episode {
    # Released in 1977
    NEWHOPE,
    # Released in 1980
    EMPIRE,
    # Released in 1983
    JEDI,
    # Released in 1999
    PHANTOM @deprecated(reason: "Not worth referencing"),
    # Released in 2002
    CLONES @deprecated  
}
```

### Schema Parser Options

For advanced use-cases, the schema parser can be tweaked to suit your needs.
Use `SchemaParserOptions.newBuilder()` to build an options object to pass to the parser.

Options:
* `genericWrappers`: Allows defining your own generic classes that should be unwrapped when matching Java types to GraphQL types.  You must supply the class and the index (zero-indexed) of the wrapped generic type.  For example: If you want to unwrap type argument `T` of `Future<T>`, you must pass `Future.class` and `0`.
* `useDefaultGenericWrappers`: Defaults to `true`.  Tells the parser whether or not to add it's own list of well-known generic wrappers, such as `Future` and `CompletableFuture`.
* `allowUnimplementedResolvers`: Defaults to `false`.  Allows a schema to be created even if not all GraphQL fields have resolvers.  Intended only for development, it will log a warning to remind you to turn it off for production.  Any unimplemented resolvers will throw errors when queried.
* `objectMapperConfigurer`: Exposes the Jackson `ObjectMapper` that handles marshalling arguments in method resolvers.  Every method resolver gets its own mapper, and the configurer can configure it differently based on the GraphQL field definition.
* `preferGraphQLResolver`: In cases where you have a Resolver class and legacy class that conflict on type arguements, use the Resolver class instead of throwing an error.

  Specificly this situation can occur when you have a graphql schema type `Foo` with a `bars` property and classes:
    ```java
    // legacy class you can't change
    class Foo {
      Set<Bar> getBars() {...returns set of bars...}
    }

    // nice resolver that does what you want
    class FooResolver implements GraphQLResolver<Foo> {
      Set<BarDTO> getBars() {...converts Bar objects to BarDTO objects and retunrs set...}
    }
    ```
    You will now have the code find two different return types for getBars() and application will not start with the error ```Caused by: com.coxautodev.graphql.tools.SchemaClassScannerError: Two different classes used for type```
    If this property is true it will ignore the legacy version.
