# GraphQL Java Tools

[![TravisCI Build](https://travis-ci.org/graphql-java/graphql-java-tools.svg?branch=master)](https://travis-ci.org/graphql-java/graphql-java-tools)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.graphql-java/graphql-java-tools/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.graphql-java/graphql-java-tools)
[![Chat on Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/graphql-java/graphql-java)

This library allows you to use the GraphQL schema language to build your [graphql-java](https://github.com/graphql-java/graphql-java) schema.
Inspired by [graphql-tools](https://github.com/apollographql/graphql-tools), it parses the given GraphQL schema and allows you to BYOO (bring your own object) to fill in the implementations.
GraphQL Java Tools works extremely well if you already have domain POJOs that hold your data (e.g. for RPC, ORM, etc) by allowing you to map these magically to GraphQL objects.

GraphQL Java Tools aims for seamless integration with Java, but works for any JVM language.  Try it with Kotlin!


## Why GraphQL Java Tools?

The main issue with graphql-java is boilerplate - it takes a lot of work to describe your GraphQL objects, and quickly becomes unreadable.
A few libraries exist to ease the boilerplate pain, but none (so far) make it easy to use existing objects to fetch data for GraphQL without code generation.
If you're using an IOC container (like Spring), this forces you to write a bunch of fragile DataFetchers.

GraphQL Java Tools gives you a simple, portable way to describe your GraphQL schema, and fill in implementations with your own object instances.
Since your GraphQL schema is independent of your data model, this makes your classes simple and extremely testable.

## Usage

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**

- [Maven/Gradle](#mavengradle)
- [Examples](#examples)
- [Defining a Schema](#defining-a-schema)
- [Resolvers and Data Classes](#resolvers-and-data-classes)
- [Enum Types](#enum-types)
- [Input Objects](#input-objects)
- [Interfaces and Union Types](#interfaces-and-union-types)
- [Scalar Types](#scalar-types)
- [Renaming Types](#renaming-types)
- [Making the graphql-java Schema Instance](#making-the-graphql-java-schema-instance)
- [GraphQL Descriptions](#graphql-descriptions)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->


### Maven/Gradle

```xml
<dependency>
    <groupId>com.graphql-java</groupId>
    <artifactId>graphql-java-tools</artifactId>
    <version>2.1.3</version>
</dependency>
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

GraphQL Java Tools maps fields on your GraphQL objects to methods on your java objects.
For most scalar fields, a POJO with getter methods is enough to describe the data to GraphQL.
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
    
    private AuthorRepostory authorRepostory;
    
    public BookResolver(AuthorRepository authorRepository) {
        this.authorRepostory = authorRepository;
    }
    
    public Author author(Book book) {
        return authorRepostory.findById(book.getAuthorId());
    }
}
```

When given a BookResolver instance, GraphQL Java Tools first attempts to map methods on the resolver before mapping them on the data class.
If there is a matching method on the resolver, the data class instance is passed as the first argument to the resolver function.

Since the Query object is a root GraphQL object, it doesn't have an associated data class:
```java
class Query implements GraphQLRootResolver {
    
    private BookRepository bookRepository;
    
    public Query(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }
    
    public List<Book> books() {
        return bookRepository.findAll();
    }
}
```

Resolvers and data classes must be provided to the schema parser:
```java
SchemaParser.newParser()
    // ...
    .resolvers(new Query(bookRepository), new BookResolver(authorRepository))
    .dictionary(Author.class)
```

*Note:* Data classes that have resolvers don't also need to be passed to `dataClasses`, such as `BookResolver` above.

*Note:* The method mapping is done by name, with unprefixed methods taking priority.
If you had a property "status" on your GraphQL object, a method named "status" on your resolver would take precedence over "getStatus" (or "isStatus" for a boolean type).
If neither of those can be found, the data class (if it exists) is then inspected for the same methods.

### Enum Types

Any enums in your java classes that map to GraphQL enums must be provided to the schema parser:
```java
SchemaParser.newParser()
    // ...
    .dictionary(Type.class, MyOtherEnum.class)
```

Enum values are automatically mapped by class name and `toString()`.

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

### Renaming Types

Sometimes your Java classes don't line up perfectly with your GraphQL schema, so GraphQL Java Tools allows you to "rename" your classes:
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

GraphQL object/field/argument descriptions can be provided by the `@doc` directive, and are added to the generated schema:

```graphql
enum Episode @doc(d: "One of the films in the Star Wars Trilogy") {
    NEWHOPE @doc(d: "Released in 1977"),
    EMPIRE @doc(d: "Released in 1980"),
    JEDI @doc(d: "Released in 1983")
}
```
### GraphQL Deprecations

GraphQL field/enum deprecations can be provided by the `@deprecated(reason: String)` directive, and are added to the 
generated schema. You can either supply a **reason** argument with a string value or not supply one and receive
a "No longer supported" message when introspected:

```graphql
enum Episode @doc(d: "One of the films in the Star Wars Trilogy") {
    NEWHOPE @doc(d: "Released in 1977"),
    EMPIRE @doc(d: "Released in 1980"),
    JEDI @doc(d: "Released in 1983"),
    PHANTOM @doc(d: "Released in 1999") @deprecated(reason: "Not worth referencing"),
    CLONES @doc(d: "Released in 2002") @deprecated  
}
```
