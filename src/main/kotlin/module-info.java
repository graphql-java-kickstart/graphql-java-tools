module graphql.java.tools {
    requires kotlin.stdlib;
    requires kotlin.reflect;
    requires kotlinx.coroutines.core;
    requires kotlinx.coroutines.reactive;

    requires com.graphqljava;
    // graphql-java's nullability annotations, only needed at compile time
    requires static org.jspecify;

    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.kotlin;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jdk8;
    requires com.fasterxml.classmate;

    requires org.apache.commons.lang3;
    requires org.reactivestreams;
    requires org.slf4j;
    requires spring.aop;

    exports graphql.kickstart.tools;

    opens graphql.kickstart.tools to com.fasterxml.jackson.databind;
}
