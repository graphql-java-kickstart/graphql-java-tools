import javassist.CtBehavior
import javassist.CtClass
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.annotation.ArrayMemberValue
import javassist.bytecode.annotation.IntegerMemberValue
import javassist.bytecode.annotation.StringMemberValue
import kotlin.metadata.Attributes
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmExtensionsKt
import kotlin.metadata.jvm.JvmMetadataUtil
import kotlin.metadata.jvm.KotlinClassMetadata

// Kotlin compiles internal declarations, lambdas and anonymous objects to public bytecode, and keeps their real visibility
// only in @kotlin.Metadata. Drop them, so only the public Kotlin API is compared.

class KotlinVisibility {
    boolean api
    Set<String> hiddenMembers
}

def API = [Visibility.PUBLIC, Visibility.PROTECTED] as Set

def readMetadata = { CtClass ctClass ->
    def ann = ctClass.classFile.getAttribute(AnnotationsAttribute.visibleTag)?.getAnnotation('kotlin.Metadata')
    if (ann == null) return null
    def value = { String n -> ann.getMemberValue(n) }
    def ints = { String n -> value(n) == null ? null : (((ArrayMemberValue) value(n)).value.collect { ((IntegerMemberValue) it).value } as int[]) }
    def strs = { String n -> value(n) == null ? null : (((ArrayMemberValue) value(n)).value.collect { ((StringMemberValue) it).value } as String[]) }
    def str = { String n -> value(n) == null ? null : ((StringMemberValue) value(n)).value }
    def i = { String n -> value(n) == null ? null : ((IntegerMemberValue) value(n)).value }
    KotlinClassMetadata.readLenient(JvmMetadataUtil.Metadata(i('k'), ints('mv'), strs('d1'), strs('d2'), str('xs'), str('pn'), i('xi')))
}

def kotlinVisibility = { CtClass ctClass ->
    def md = readMetadata(ctClass)
    if (md == null) return null
    if (md instanceof KotlinClassMetadata.SyntheticClass) return new KotlinVisibility(api: false, hiddenMembers: [] as Set)
    def hidden = [] as Set
    def hide = { declaration, signature ->
        if (signature && (declaration == null || !(Attributes.getVisibility(declaration) in API))) hidden << signature.name + signature.descriptor
    }
    def addFunctions = { functions -> functions.each { hide(it, JvmExtensionsKt.getSignature(it)) } }
    def addProperties = { properties ->
        properties.each { p ->
            hide(p.getter, JvmExtensionsKt.getGetterSignature(p))
            hide(p.setter, JvmExtensionsKt.getSetterSignature(p))
        }
    }
    if (md instanceof KotlinClassMetadata.Class) {
        def km = md.kmClass
        addFunctions(km.functions)
        addProperties(km.properties)
        km.constructors.each { hide(it, JvmExtensionsKt.getSignature(it)) }
        return new KotlinVisibility(api: Attributes.getVisibility(km) in API, hiddenMembers: hidden)
    }
    if (md instanceof KotlinClassMetadata.FileFacade || md instanceof KotlinClassMetadata.MultiFileClassPart) {
        addFunctions(md.kmPackage.functions)
        addProperties(md.kmPackage.properties)
    }
    new KotlinVisibility(api: true, hiddenMembers: hidden)
}

def info = jApiClasses.collectEntries { c ->
    [c.fullyQualifiedName, [old: c.oldClass.map { kotlinVisibility(it) }.orElse(null), new: c.newClass.map { kotlinVisibility(it) }.orElse(null)]]
}
def isApiClass = { String name ->
    def sides = [info[name]?.old, info[name]?.new].findAll { it != null }
    sides.isEmpty() || sides.any { it.api }
}
def nonApiNames = info.keySet().findAll { !isApiClass(it) }
def nestedInNonApi = { String name -> nonApiNames.any { name.startsWith(it + '$') } }

def hiddenOn = { side, CtBehavior b -> side != null && (b.methodInfo.name + b.methodInfo.descriptor) in side.hiddenMembers }
def isHidden = { d, Optional oldB, Optional newB ->
    d != null && oldB.map { hiddenOn(d.old, it) }.orElse(true) && newB.map { hiddenOn(d.new, it) }.orElse(true)
}

// japicmp ignores the list this script returns and reads the one it passed in, so edit that one in place.
def nonApiClass = { !isApiClass(it.fullyQualifiedName) || nestedInNonApi(it.fullyQualifiedName) }
def droppedClasses = jApiClasses.count(nonApiClass)
jApiClasses.removeIf(nonApiClass)
def droppedMembers = 0
jApiClasses.each { c ->
    def d = info[c.fullyQualifiedName]
    def hiddenMethod = { isHidden(d, it.oldMethod, it.newMethod) }
    def hiddenConstructor = { isHidden(d, it.oldConstructor, it.newConstructor) }
    droppedMembers += c.methods.count(hiddenMethod) + c.constructors.count(hiddenConstructor)
    c.methods.removeIf(hiddenMethod)
    c.constructors.removeIf(hiddenConstructor)
}
println "[japicmp-kotlin-public] dropped ${droppedClasses} non-API classes and ${droppedMembers} non-API members"

def breaking = { element -> element.compatibilityChanges.findAll { !it.binaryCompatible }*.type }
def isDataClass = { CtClass ct ->
    def md = ct == null ? null : readMetadata(ct)
    md instanceof KotlinClassMetadata.Class && Attributes.isData(md.kmClass)
}
def problems = []
info.each { name, d ->
    if (d.old?.api && d.new != null && !d.new.api && !nestedInNonApi(name)) problems << "  ${name}: public in the last release, now internal or private in Kotlin"
}
jApiClasses.each { c ->
    def name = c.fullyQualifiedName
    def simpleName = name.substring(name.lastIndexOf('.') + 1)
    def lines = []
    breaking(c).each { lines << "  ${name}: ${it}" }
    breaking(c.superclass).each { lines << "  ${name} superclass: ${it}" }
    c.interfaces.each { i -> breaking(i).each { lines << "  ${name} implements ${i.fullyQualifiedName}: ${it}" } }
    c.constructors.each { m -> breaking(m).each { lines << "  ${name}#${simpleName}(${m.parameters*.type.join(', ')}): ${it}" } }
    c.methods.each { m -> breaking(m).each { lines << "  ${name}#${m.name}(${m.parameters*.type.join(', ')}): ${it}" } }
    c.fields.each { f -> breaking(f).each { lines << "  ${name}#${f.name}: ${it}" } }
    if (lines.isEmpty()) return
    problems.addAll(lines)
    if (isDataClass(c.oldClass.orElse(null)) && c.methods.any { it.name == 'copy' && !breaking(it).isEmpty() }) {
        problems << "  -> ${name} is a Kotlin data class, so a new or changed property changes its constructor and copy() for " +
            "compiled callers. Keep the old copy() as a @Deprecated(level = DeprecationLevel.HIDDEN) overload that forwards to the new " +
            "one, and keep the old constructor with @JvmOverloads or a secondary constructor."
    }
}
if (!problems.isEmpty()) {
    throw new IllegalStateException("Binary-incompatible change to the public API since the last release:\n" + problems.join('\n') +
        "\nCode compiled against the last release fails with NoSuchMethodError or a similar linkage error. Keep the old signature " +
        "as a @Deprecated overload. In Kotlin, @Deprecated(level = DeprecationLevel.HIDDEN) keeps it for compiled callers only. " +
        "If the break is planned for the next major, add the text before the colon as an <exclude> under <parameter><excludes> " +
        "of japicmp-maven-plugin in pom.xml, with a TODO comment naming that major.")
}
jApiClasses
