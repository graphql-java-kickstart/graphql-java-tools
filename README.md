# GraphQL Java Tools

[![Github Build](https://github.com/graphql-java-kickstart/graphql-java-tools/actions/workflows/snapshot.yml/badge.svg)](https://github.com/graphql-java-kickstart/graphql-java-tools/actions/workflows/snapshot.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.graphql-java-kickstart/graphql-java-tools/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.graphql-java-kickstart/graphql-java-tools)
[![Discuss on GitHub](https://img.shields.io/badge/GitHub-discuss-orange)](https://github.com/graphql-java-kickstart/graphql-java-tools/discussions)

This library allows you to use the GraphQL schema language to build your [graphql-java](https://github.com/graphql-java/graphql-java) schema.
Inspired by [graphql-tools](https://github.com/apollographql/graphql-tools), it parses the given GraphQL schema and allows you to BYOO (bring your own object) to fill in the implementations.
GraphQL Java Tools works well if you already have domain POJOs that hold your data (e.g. for RPC, ORM, REST, etc) by allowing you to map these "magically" to GraphQL objects.

GraphQL Java Tools aims for seamless integration with Java, but works for any JVM language.  Try it with Kotlin!

## We are looking for contributors!

Are you interested in improving our documentation, working on the codebase, reviewing PRs?

[Reach out to us on GitHub](https://github.com/graphql-java-kickstart/graphql-java-tools/discussions) and join the team!

## Quick start

### Using Gradle
Set the Kotlin version in your `gradle.properties`:
```
kotlin.version=1.7.10
```

Add the dependency:
```groovy
compile 'com.graphql-java-kickstart:graphql-java-tools:13.0.1'
```

### Using Maven
Set the Kotlin version in your `<properties>` section:
```xml

<properties>
    <kotlin.version>1.7.10</kotlin.version>
</properties>
```

Add the dependency:
```xml
<dependency>
    <groupId>com.graphql-java-kickstart</groupId>
    <artifactId>graphql-java-tools</artifactId>
    <version>13.0.1</version>
</dependency>
```

## Documentation

Take a look at our [documentation](https://www.graphql-java-kickstart.com/tools/) for more details.

## Why GraphQL Java Tools?

* **Schema First**:  GraphQL Java Tools allows you to write your schema in a simple, portable way using the [GraphQL schema language](http://graphql.org/learn/schema/) instead of hard-to-read builders in code.
* **Minimal Boilerplate**:  It takes a lot of work to describe your GraphQL-Java objects manually, and quickly becomes unreadable.
A few libraries exist to ease the boilerplate pain, including [GraphQL-Java's built-in schema-first wiring](https://www.graphql-java.com/documentation/master/schema/), but none (so far) do type and datafetcher discovery.
* **Stateful Data Fetchers**:  If you're using an IOC container (like Spring), it's hard to wire up datafetchers that make use of beans you've already defined without a bunch of fragile configuration.  GraphQL Java Tools allows you to register "Resolvers" for any type that can bring state along and use that to resolve fields.
* **Generated DataFetchers**:  GraphQL Java Tools automatically creates data fetchers for your fields that call the appropriate method on your java class.  This means all you have to do to create a new field is add the field definition to your schema and add a corresponding method on your class.
* **Type->Class Discovery**:  GraphQL Java Tools starts from your root objects (Query, Mutation) and, as it's generating data fetchers for you, starts to learn about the classes you use for a certain GraphQL type.
* **Class Validation**:  Since there aren't any compile-time checks of the type->class relationship, GraphQL Java Tools will warn you if you provide classes/types that you don't need to, as well as erroring if you use the wrong Java class for a certain GraphQL type when it builds the schema.
* **Unit Testing**:  Since your GraphQL schema is independent of your data model, this makes your classes simple and extremely testable.

## Known Issues

[Known issues are aggregated at the wiki](https://github.com/graphql-java-kickstart/graphql-java-tools/wiki/Known-Issues).

