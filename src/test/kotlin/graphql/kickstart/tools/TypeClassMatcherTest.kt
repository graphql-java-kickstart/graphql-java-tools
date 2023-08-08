package graphql.kickstart.tools

import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper.Companion.listCollectionWithTransformer
import graphql.kickstart.tools.resolver.FieldResolverScanner
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.language.*
import graphql.language.Type
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Suite
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.Future

@RunWith(Suite::class)
@Suite.SuiteClasses(
    TypeClassMatcherTest.Suit1::class,
    TypeClassMatcherTest.Suit2::class,
    TypeClassMatcherTest.Suit3::class,
    TypeClassMatcherTest.Suit4::class
)
@OptIn(ExperimentalCoroutinesApi::class)
class TypeClassMatcherTest {

    companion object {
        private val customType: Type<*> = TypeName("CustomType")
        private val unwrappedCustomType: Type<*> = TypeName("UnwrappedGenericCustomType")

        private val customDefinition: TypeDefinition<*> = ObjectTypeDefinition("CustomType")
        private val unwrappedCustomDefinition: TypeDefinition<*> = ObjectTypeDefinition("UnwrappedGenericCustomType")

        private val matcher: TypeClassMatcher = TypeClassMatcher(mapOf(
            "CustomType" to customDefinition,
            "UnwrappedGenericCustomType" to unwrappedCustomDefinition
        ))

        private val options: SchemaParserOptions = SchemaParserOptions.newOptions().genericWrappers(
            GenericWrapper(
                GenericCustomType::class.java,
                0
            ),
            listCollectionWithTransformer(
                GenericCustomListType::class.java,
                0
            ) { x -> x }
        ).build()

        private val scanner: FieldResolverScanner = FieldResolverScanner(options)
        private val resolver = RootResolverInfo(listOf(QueryMethods()), options)

        private fun createPotentialMatch(methodName: String, graphQLType: Type<*>): TypeClassMatcher.PotentialMatch {
            return scanner.findFieldResolver(FieldDefinition(methodName, graphQLType), resolver)
                .scanForMatches()
                .find { it.location == TypeClassMatcher.Location.RETURN_TYPE }!!
        }

        private fun list(other: Type<*> = customType): Type<*> = ListType(other)
        private fun nonNull(other: Type<*> = customType): Type<*> = NonNullType(other)
    }

    @RunWith(Parameterized::class)
    class Suit1(private val methodName: String, private val type: Type<*>) {

        @Test
        fun `matcher verifies that nested return type matches graphql definition for method`() {
            val match = matcher.match(createPotentialMatch(methodName, type))
            match as TypeClassMatcher.ValidMatch
            assertEquals(match.type, customDefinition)
            assertEquals(match.javaType, CustomType::class.java)
        }

        companion object {
            @Parameterized.Parameters
            @JvmStatic
            fun data(): Collection<Array<Any>> {
                return listOf(
                    arrayOf("type", customType),
                    arrayOf("futureType", customType),
                    arrayOf("listType", list()),
                    arrayOf("listListType", list(list())),
                    arrayOf("futureListType", list()),
                    arrayOf("listFutureType", list()),
                    arrayOf("listListFutureType", list(list())),
                    arrayOf("futureListListType", list(list())),
                    arrayOf("superType", customType),
                    arrayOf("superListFutureType", list(nonNull())),
                    arrayOf("nullableType", customType),
                    arrayOf("nullableListType", list(nonNull(customType))),
                    arrayOf("genericCustomType", customType),
                    arrayOf("genericListType", list())
                )
            }
        }
    }

    @RunWith(Parameterized::class)
    class Suit2(private val methodName: String, private val type: Type<*>) {

        @Test(expected = SchemaClassScannerError::class)
        fun `matcher verifies that nested return type doesn't match graphql definition for method`() {
            matcher.match(createPotentialMatch(methodName, type))
        }

        companion object {
            @Parameterized.Parameters
            @JvmStatic
            fun data(): Collection<Array<Any>> {
                return listOf(
                    arrayOf("type", list()),
                    arrayOf("futureType", list())
                )
            }
        }
    }

    @RunWith(Parameterized::class)
    class Suit3(private val methodName: String, private val type: Type<*>) {

        @Test(expected = SchemaClassScannerError::class)
        fun `matcher verifies return value optionals are used incorrectly for method`() {
            matcher.match(createPotentialMatch(methodName, type))
        }

        companion object {
            @Parameterized.Parameters
            @JvmStatic
            fun data(): Collection<Array<Any>> {
                return listOf(
                    arrayOf("nullableType", nonNull(customType)),
                    arrayOf("nullableNullableType", customType),
                    arrayOf("listNullableType", list(customType))
                )
            }
        }
    }

    class Suit4 {
        @Test
        fun `matcher allows unwrapped parameterized types as root types`() {
            val match = matcher.match(createPotentialMatch("genericCustomUnwrappedType", unwrappedCustomType))
            match as TypeClassMatcher.ValidMatch
            assertEquals(match.type, unwrappedCustomDefinition)
            val javatype = match.javaType as ParameterizedTypeImpl
            assertEquals(javatype.rawType, UnwrappedGenericCustomType::class.java)
            assertEquals(javatype.actualTypeArguments.first(), CustomType::class.java)
        }
    }

    private abstract class Super<Unused, Type, ListFutureType> : GraphQLQueryResolver {
        fun superType(): Type = superType()
        fun superListFutureType(): ListFutureType = superListFutureType()
    }

    private class QueryMethods : Super<Void, CustomType, List<CompletableFuture<CustomType>>>() {
        fun type(): CustomType = CustomType()
        fun futureType(): Future<CustomType> = completedFuture(CustomType())
        fun listType(): List<CustomType> = listOf(CustomType())
        fun listListType(): List<List<CustomType>> = listOf(listOf(CustomType()))
        fun futureListType(): CompletableFuture<List<CustomType>> = completedFuture(listOf(CustomType()))
        fun listFutureType(): List<CompletableFuture<CustomType>> = listOf(completedFuture(CustomType()))
        fun listListFutureType(): List<List<CompletableFuture<CustomType>>> = listOf(listOf(completedFuture(CustomType())))
        fun futureListListType(): CompletableFuture<List<List<CustomType>>> = completedFuture(listOf(listOf(CustomType())))
        fun nullableType(): Optional<CustomType?>? = null
        fun nullableListType(): Optional<List<CustomType>?>? = null
        fun nullableNullableType(): Optional<Optional<CustomType?>?>? = null
        fun listNullableType(): List<Optional<CustomType?>?> = listOf(null)
        fun genericCustomType(): GenericCustomType<CustomType> = GenericCustomType()
        fun genericListType(): GenericCustomListType<CustomType> = GenericCustomListType()
        fun genericCustomUnwrappedType(): UnwrappedGenericCustomType<CustomType> = UnwrappedGenericCustomType()
    }

    private class CustomType

    private class GenericCustomType<T>

    private class GenericCustomListType<T>

    private class UnwrappedGenericCustomType<T>
}
