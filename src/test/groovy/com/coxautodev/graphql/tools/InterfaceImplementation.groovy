package com.coxautodev.graphql.tools

class InterfaceImplementation implements GraphQLQueryResolver {
    NamedResource query1() { null }
    NamedResourceImpl query2() { null }

    static class NamedResourceImpl implements NamedResource {
        String name() {}
    }
}
