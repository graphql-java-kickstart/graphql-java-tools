package graphql.kickstart.tools

class MultipleInterfaces implements GraphQLQueryResolver {
    NamedResourceImpl query1() { null }

    VersionedResourceImpl query2() { null }

    static class NamedResourceImpl implements NamedResource {
        String name() {}
    }

    static class VersionedResourceImpl implements VersionedResource {
        int version() {}
    }
}


