package graphql.kickstart.tools

interface Animal {
    fun type(): SchemaClassScannerTest.NestedInterfaceTypeQuery.ComplexType?
}

interface Vehicle {
    fun getInformation(): VehicleInformation
}

interface VehicleInformation

interface SomeInterface {
    fun getValue(): String?
}

interface SomeUnion

enum class SomeEnum {
    A,
    B
}

