import javassist.CtBehavior
import javassist.CtClass
import javassist.bytecode.AccessFlag
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.InnerClassesAttribute
import javassist.bytecode.annotation.ArrayMemberValue
import javassist.bytecode.annotation.IntegerMemberValue
import javassist.bytecode.annotation.StringMemberValue
import kotlin.metadata.Attributes
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmExtensionsKt
import kotlin.metadata.jvm.JvmMetadataUtil
import kotlin.metadata.jvm.KotlinClassMetadata

import java.net.JarURLConnection

// Kotlin compiles internal declarations, lambdas and anonymous objects to public bytecode, and keeps their real visibility
// only in @kotlin.Metadata. It also marks members that compiled callers still link to as synthetic. Compare only what a
// compiled Kotlin or Java caller can link to.

class ClassSide {
    boolean ownApi
    String outerName
    Set<String> hidden = [] as Set
    Set<String> apiMembers = [] as Set
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

def outerNameOf = { CtClass ctClass ->
    def inner = (InnerClassesAttribute) ctClass.classFile.getAttribute(InnerClassesAttribute.tag)
    def index = inner == null ? null : (0..<inner.tableLength()).find { inner.innerClass(it) == ctClass.name }
    index == null ? null : inner.outerClass(index)
}

def isSynthetic = { int accessFlags -> (accessFlags & AccessFlag.SYNTHETIC) != 0 }

def classSide = { CtClass ctClass ->
    def side = new ClassSide(ownApi: !isSynthetic(ctClass.classFile.accessFlags), outerName: outerNameOf(ctClass))
    def md = readMetadata(ctClass)
    if (md == null) return side
    if (md instanceof KotlinClassMetadata.SyntheticClass) {
        side.ownApi = false
        return side
    }
    def owner = 'L' + ctClass.name.replace('.', '/') + ';'
    // A caller that omits a default argument links to the synthetic name$default bridge, or the synthetic constructor
    // that takes a DefaultConstructorMarker.
    def defaultBridge = { declaration, signature, String receiver, String marker ->
        if (!declaration.valueParameters.any { Attributes.getDeclaresDefaultValue(it) }) return null
        def masks = 'I' * ((declaration.valueParameters.size() + 31).intdiv(32))
        def params = signature.descriptor.substring(1, signature.descriptor.indexOf(')'))
        def name = signature.name == '<init>' ? '<init>' : signature.name + '$default'
        name + '(' + receiver + params + masks + marker + signature.descriptor.substring(signature.descriptor.indexOf(')'))
    }
    def add = { declaration, signature, String bridge = null ->
        if (signature == null) return
        def api = declaration != null && Attributes.getVisibility(declaration) in API
        if (!api) {
            side.hidden << signature.name + signature.descriptor
            return
        }
        side.apiMembers << signature.name + signature.descriptor
        if (bridge != null) side.apiMembers << bridge
    }
    def addFunctions = { functions, String receiver ->
        functions.each { f ->
            def signature = JvmExtensionsKt.getSignature(f)
            add(f, signature, signature == null ? null : defaultBridge(f, signature, receiver, 'Ljava/lang/Object;'))
        }
    }
    def addProperties = { properties ->
        properties.each { p ->
            add(p.getter, JvmExtensionsKt.getGetterSignature(p))
            add(p.setter, JvmExtensionsKt.getSetterSignature(p))
        }
    }
    if (md instanceof KotlinClassMetadata.Class) {
        def km = md.kmClass
        addFunctions(km.functions, owner)
        addProperties(km.properties)
        km.constructors.each { c ->
            def signature = JvmExtensionsKt.getSignature(c)
            add(c, signature, signature == null ? null : defaultBridge(c, signature, '', 'Lkotlin/jvm/internal/DefaultConstructorMarker;'))
        }
        side.ownApi = side.ownApi && Attributes.getVisibility(km) in API
    }
    if (md instanceof KotlinClassMetadata.FileFacade || md instanceof KotlinClassMetadata.MultiFileClassPart) {
        addFunctions(md.kmPackage.functions, '')
        addProperties(md.kmPackage.properties)
        // A file facade holds only top-level declarations, so with none public it has nothing to link to.
        side.ownApi = side.ownApi && !side.apiMembers.isEmpty()
    }
    side
}

// japicmp hands this script only the classes, so the excludes come from its options and the version from the pom.properties
// maven-jar-plugin puts in the jar under comparison.
def excludes = jApiClasses.isEmpty() ? [] : jApiClasses.first().@options.filters.excludes*.toString().unique()
if (!excludes.isEmpty()) {
    def newClass = jApiClasses.find { it.newClass.present }.newClass.get()
    def jar = ((JarURLConnection) newClass.classPool.find(newClass.name).openConnection()).jarFile
    def pomProperties = new Properties()
    pomProperties.load(jar.getInputStream(jar.entries().find { it.name.startsWith('META-INF/maven/') && it.name.endsWith('/pom.properties') }))
    def version = pomProperties.getProperty('version')
    if (!(version ==~ /\d+\.0\.0(-SNAPSHOT)?/)) {
        throw new IllegalStateException("japicmp-maven-plugin has <excludes> at version ${version}, which compares against a " +
            "release of the same major:\n" + excludes.collect { "  ${it}" }.join('\n') + "\nExcludes are only for breaks planned for the next major, " +
            "with the version set to that major's X.0.0-SNAPSHOT. Remove them and keep the old signature as a @Deprecated overload " +
            "instead. In Kotlin, @Deprecated(level = DeprecationLevel.HIDDEN) keeps it for compiled callers only.")
    }
}

def info = jApiClasses.collectEntries { c ->
    [c.fullyQualifiedName, [old: c.oldClass.map { classSide(it) }.orElse(null), new: c.newClass.map { classSide(it) }.orElse(null)]]
}
// japicmp leaves out classes that aren't public in bytecode, such as a private Kotlin class, so a class whose outer class
// is missing here is nested in one of those.
def isApiOn
isApiOn = { String name, String sideName ->
    def side = info[name]?.get(sideName)
    side != null && side.ownApi && (side.outerName == null || isApiOn(side.outerName, sideName))
}
def isApiClass = { String name -> ['old', 'new'].any { isApiOn(name, it) } }

def memberApiOn = { ClassSide side, String signature, int accessFlags ->
    !(signature in side.hidden) && (!isSynthetic(accessFlags) || signature in side.apiMembers)
}
def behaviorApiOn = { ClassSide side, CtBehavior b -> memberApiOn(side, b.methodInfo.name + b.methodInfo.descriptor, b.methodInfo.accessFlags) }
def isApiBehavior = { d, Optional oldB, Optional newB ->
    oldB.map { behaviorApiOn(d.old, it) }.orElse(false) || newB.map { behaviorApiOn(d.new, it) }.orElse(false)
}
def isApiField = { f ->
    f.oldFieldOptional.map { !isSynthetic(it.fieldInfo2.accessFlags) }.orElse(false) ||
        f.newFieldOptional.map { !isSynthetic(it.fieldInfo2.accessFlags) }.orElse(false)
}

// japicmp ignores the list this script returns and reads the one it passed in, so edit that one in place.
def nonApiClass = { !isApiClass(it.fullyQualifiedName) }
def droppedClasses = jApiClasses.count(nonApiClass)
jApiClasses.removeIf(nonApiClass)
def droppedMembers = 0
jApiClasses.each { c ->
    def d = info[c.fullyQualifiedName]
    def nonApiMethod = { !isApiBehavior(d, it.oldMethod, it.newMethod) }
    def nonApiConstructor = { !isApiBehavior(d, it.oldConstructor, it.newConstructor) }
    def nonApiField = { !isApiField(it) }
    droppedMembers += c.methods.count(nonApiMethod) + c.constructors.count(nonApiConstructor) + c.fields.count(nonApiField)
    c.methods.removeIf(nonApiMethod)
    c.constructors.removeIf(nonApiConstructor)
    c.fields.removeIf(nonApiField)
}
println "[japicmp-kotlin-public] dropped ${droppedClasses} non-API classes and ${droppedMembers} non-API members"

def breaking = { element -> element.compatibilityChanges.findAll { !it.binaryCompatible }*.type }
def isDataClass = { CtClass ct ->
    def md = ct == null ? null : readMetadata(ct)
    md instanceof KotlinClassMetadata.Class && Attributes.isData(md.kmClass)
}
def problems = []
info.each { name, d ->
    if (isApiOn(name, 'old') && d.new != null && !d.new.ownApi) problems << "  ${name}: public in the last release, now internal or private in Kotlin"
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
    if (isDataClass(c.oldClass.orElse(null)) && c.methods.any { it.name.startsWith('copy') && !breaking(it).isEmpty() }) {
        problems << "  -> ${name} is a Kotlin data class, so a new or changed property changes its constructor and copy() for " +
            "compiled callers. Keep the old copy() as a @Deprecated(level = DeprecationLevel.HIDDEN) overload with the same " +
            "default arguments that forwards to the new one, and keep the old constructor with @JvmOverloads or a secondary constructor."
    } else if (lines.any { it.contains('$default(') || it.contains('kotlin.jvm.internal.DefaultConstructorMarker)') }) {
        problems << "  -> A name\$default method or a constructor ending in DefaultConstructorMarker is what Kotlin callers link to when " +
            "they leave out a default argument. Keep the default argument. If the parameters changed, keep the old signature as a " +
            "@Deprecated(level = DeprecationLevel.HIDDEN) overload with the same default arguments."
    }
}
if (!problems.isEmpty()) {
    throw new IllegalStateException("Binary-incompatible change to the public API since the last release:\n" + problems.join('\n') +
        "\nCode compiled against the last release fails with NoSuchMethodError or a similar linkage error. Keep the old signature " +
        "as a @Deprecated overload. In Kotlin, @Deprecated(level = DeprecationLevel.HIDDEN) keeps it for compiled callers only. " +
        "If the break is planned for the next major, set the version in pom.xml to that major's X.0.0-SNAPSHOT and add the text " +
        "before the colon as an <exclude> under <parameter><excludes> of japicmp-maven-plugin, with a TODO comment naming that major.")
}
jApiClasses
