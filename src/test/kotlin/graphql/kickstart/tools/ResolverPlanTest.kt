package graphql.kickstart.tools

import graphql.kickstart.tools.SchemaParserOptions.Companion.defaultOptions
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.ResolverPlan
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.TypeName
import graphql.relay.Connection
import graphql.relay.DefaultConnection
import graphql.relay.DefaultPageInfo
import graphql.schema.DataFetchingEnvironment
import org.junit.Test
import java.util.*

class ResolverPlanTest {

    private val options = defaultOptions()
    private val finder = ResolverPlan.Finder(options, GRAPHQL_SCALARS.keys)

    private fun ResolverPlan.reference() = typeShapes().last().reference.toString()

    @Test
    fun `finder finds fields on multiple root types`() {
        val resolver = RootResolverInfo(listOf(RootQuery1(), RootQuery2()), options)

        val result1 = finder.find(FieldDefinition("field1", TypeName("String")), resolver)
        val result2 = finder.find(FieldDefinition("field2", TypeName("String")), resolver)

        assertNotEquals(result1.search.source, result2.search.source)
    }

    @Test(expected = FieldResolverError::class)
    fun `finder throws exception when more than one resolver method is found`() {
        val resolver = RootResolverInfo(listOf(RootQuery1(), DuplicateQuery()), options)

        finder.find(FieldDefinition("field1", TypeName("String")), resolver)
    }

    @Test(expected = FieldResolverError::class)
    fun `finder throws exception when no resolver methods are found`() {
        val resolver = RootResolverInfo(listOf(), options)

        finder.find(FieldDefinition("field1", TypeName("String")), resolver)
    }

    @Test
    fun `finder finds properties when no method is found`() {
        val resolver = RootResolverInfo(listOf(PropertyQuery()), options)

        val name = finder.find(FieldDefinition("name", TypeName("String")), resolver)
        val version = finder.find(FieldDefinition("version", TypeName("Integer")), resolver)

        assertEquals(name.reference(), "type of field ${PropertyQuery::class.java.getDeclaredField("name")}")
        assertEquals(version.reference(), "type of field ${ParentPropertyQuery::class.java.getDeclaredField("version")}")
    }

    @Test
    fun `finder finds generic return type`() {
        val resolver = RootResolverInfo(listOf(GenericQuery()), options)

        val users = finder.find(FieldDefinition("users", TypeName("UserConnection")), resolver)

        assertEquals(users.reference(), "return type of method ${GenericQuery::class.java.getMethod("getUsers")}")
    }

    @Test
    fun `finder prefers concrete resolver`() {
        val resolver = DataClassResolverInfo(Kayak::class.java)

        val meta = finder.find(FieldDefinition("information", TypeName("VehicleInformation")), resolver)

        assertEquals(meta.typeShapes().last().leafJavaType, BoatInformation::class.java)
    }

    @Test
    fun `finder finds field resolver method using camelCase for snake_cased field_name`() {
        val resolver = RootResolverInfo(listOf(CamelCaseQuery1()), options)

        val meta = finder.find(FieldDefinition("hull_type", TypeName("HullType")), resolver)

        assertEquals(meta.reference(), "return type of method ${CamelCaseQuery1::class.java.getMethod("getHullType")}")
    }

    @Test
    fun `finder finds field resolver method using capitalize field_name in different locale`() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        val resolverInfo = RootResolverInfo(listOf(CapitalizeQuery()), options)
        val plan = finder.find(FieldDefinition("id", TypeName("HullType")), resolverInfo)

        assertEquals(plan.reference(), "return type of method ${CapitalizeQuery::class.java.getMethod("getId")}")

        Locale.setDefault(default)
    }

    @Test
    fun `finder ignores extension functions on root resolvers`() {
        val resolver = RootResolverInfo(listOf(UserQuery(), ExtensionQuery()), options)
        val field = FieldDefinition.newFieldDefinition()
            .name("user")
            .type(TypeName("String"))
            .inputValueDefinition(InputValueDefinition("id", TypeName("ID")))
            .build()

        val user = finder.find(field, resolver)

        assertEquals(user.reference(), "return type of method ${UserQuery::class.java.getMethod("user", String::class.java)}")
    }

    @Test
    fun `finder finds field resolver methods in priority order`() {
        val resolverInfo = RootResolverInfo(listOf(PriorityQuery()), options)

        fun methodName(name: String, type: String) =
            finder.find(FieldDefinition(name, TypeName(type)), resolverInfo).reference().substringAfter("${PriorityQuery::class.java.name}.").substringBefore("(")

        assertEquals(methodName("name", "String"), "name")
        assertEquals(methodName("active", "Boolean"), "isActive")
        assertEquals(methodName("enabled", "String"), "getEnabled")
        assertEquals(methodName("count", "Int"), "getFieldCount")
    }

    @Test
    fun `finder takes a method before a field and a field before a Map key`() {
        fun reference(dataClass: Class<*>) = finder.find(FieldDefinition("value", TypeName("String")), DataClassResolverInfo(dataClass)).reference()

        assertEquals(reference(MapWithMethod::class.java), "return type of method ${MapWithMethod::class.java.getMethod("getValue")}")
        assertEquals(reference(MapWithField::class.java), "type of field ${MapWithField::class.java.getField("value")}")
        assertEquals(reference(PlainMap::class.java), "type of field value")
    }

    class PlainMap : HashMap<String, String>()

    class MapWithField : HashMap<String, String>() {
        @JvmField
        val value: String = "field"
    }

    class MapWithMethod : HashMap<String, String>() {
        @JvmField
        val value: String = "field"

        fun getValue(): String = "method"
    }

    class RootQuery1 : GraphQLQueryResolver {
        fun field1() {}
    }

    class RootQuery2 : GraphQLQueryResolver {
        fun field2() {}
    }

    class DuplicateQuery : GraphQLQueryResolver {
        fun field1() {}
    }

    class UserQuery : GraphQLQueryResolver {
        fun user(id: String): String = id
    }

    class ExtensionQuery : GraphQLQueryResolver {
        fun DataFetchingEnvironment.user(): String = field.name
    }

    class CamelCaseQuery1 : GraphQLQueryResolver {
        fun getHullType(): HullType = HullType()
    }

    class CapitalizeQuery : GraphQLQueryResolver {
        fun getId(): HullType = HullType()
    }

    class PriorityQuery : GraphQLQueryResolver {
        fun name(): String = "name"
        fun getName(): String = "name"
        fun getFieldName(): String = "name"
        fun active(flag: Boolean): Boolean = flag
        fun isActive(): Boolean = true
        fun getActive(): Boolean = true
        fun isEnabled(): Boolean = true
        fun getEnabled(): String = "enabled"
        fun getFieldCount(): Int = 1
    }

    class HullType

    open class ParentPropertyQuery {
        private var version: Int = 1
    }

    class PropertyQuery : ParentPropertyQuery(), GraphQLQueryResolver {
        private var name: String = "name"
    }

    class User

    class GenericQuery : GraphQLQueryResolver {
        fun getUsers(): Connection<User> {
            return DefaultConnection(listOf(), DefaultPageInfo(null, null, false, false))
        }
    }

    abstract class Boat : Vehicle {
        override fun getInformation(): BoatInformation = this.getInformation()
    }

    class BoatInformation : VehicleInformation

    class Kayak : Boat()
}
