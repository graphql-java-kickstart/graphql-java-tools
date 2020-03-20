package graphql.kickstart.tools

fun createLongSchema() = SchemaParser.newParser()
    .schemaString(schemaDefinition)
    .resolvers(LongQuery())
    .build()
    .makeExecutableSchema()

private const val schemaDefinition = """

"""

class LongQuery : GraphQLQueryResolver {
  fun itemByLongId(id: Long) = LongItem(id)
  fun itemsByListOfLongId(ids: List<Long>) = ids.map { id -> LongItem(id) }
}

data class LongItem(val id: Long)
