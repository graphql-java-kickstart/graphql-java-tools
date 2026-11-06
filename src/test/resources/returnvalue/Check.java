package unwrapcheck;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.kickstart.tools.GraphQLResolver;
import graphql.kickstart.tools.SchemaParser;
import graphql.kickstart.tools.SchemaParserBuilder;
import graphql.kickstart.tools.SchemaParserOptions;
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLSchema;
import unwrapcheck.Fixtures.FutureItem;
import unwrapcheck.Fixtures.Later;
import unwrapcheck.Fixtures.Page;
import unwrapcheck.Fixtures.Payload;
import unwrapcheck.Fixtures.Try;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Check {

    enum Container {
        LIST("java.util.List", Variant.VALUE, Variant.NULL, Variant.EMPTY),
        PAGE("unwrapcheck.Fixtures.Page", Variant.VALUE, Variant.NULL, Variant.EMPTY),
        COMPLETABLE_FUTURE("java.util.concurrent.CompletableFuture", Variant.VALUE, Variant.NULL, Variant.FAILED),
        FUTURE("java.util.concurrent.Future", Variant.VALUE, Variant.NULL, Variant.FUTURE_TASK),
        LATER("unwrapcheck.Fixtures.Later", Variant.VALUE, Variant.NULL, Variant.FAILED),
        TRY("unwrapcheck.Fixtures.Try", Variant.VALUE, Variant.NULL, Variant.FAILED),
        DATA_FETCHER_RESULT("graphql.execution.DataFetcherResult", Variant.VALUE, Variant.NULL, Variant.WITH_ERROR),
        OPTIONAL("java.util.Optional", Variant.VALUE, Variant.EMPTY);

        final String javaName;
        final List<Variant> variants;

        Container(String javaName, Variant... variants) {
            this.javaName = javaName;
            this.variants = List.of(variants);
        }

        boolean isList() {
            return this == LIST || this == PAGE;
        }

        boolean isFuture() {
            return this == COMPLETABLE_FUTURE || this == FUTURE || this == LATER;
        }
    }

    enum Leaf {
        PAYLOAD("unwrapcheck.Fixtures.Payload", "Payload", " { data }"),
        STRING("java.lang.String", "String", ""),
        OBJECT("java.lang.Object", "Item", " { __typename ... on Payload { data } ... on FutureItem { data } }");

        final String javaName;
        final String graphQLName;
        final String selection;

        Leaf(String javaName, String graphQLName, String selection) {
            this.javaName = javaName;
            this.graphQLName = graphQLName;
            this.selection = selection;
        }
    }

    enum Kind {
        METHOD("method", "method", List.of(Nullability.values()), List.of(Timing.values())),
        PROPERTY("property", "field", List.of(Nullability.NULLABLE), List.of(Timing.DONE, Timing.PENDING_IN_ORDER)),
        MAP("map", "map value", List.of(Nullability.NULLABLE), List.of(Timing.DONE, Timing.PENDING_IN_ORDER));

        final String label;
        final String resolverKind;
        final List<Nullability> nullabilities;
        final List<Timing> timings;

        Kind(String label, String resolverKind, List<Nullability> nullabilities, List<Timing> timings) {
            this.label = label;
            this.resolverKind = resolverKind;
            this.nullabilities = nullabilities;
            this.timings = timings;
        }
    }

    enum Nullability {NULLABLE, NON_NULL}

    enum Variant {VALUE, NULL, EMPTY, FAILED, FUTURE_TASK, WITH_ERROR, FUTURE_ITEM}

    enum Timing {
        DONE("before the data fetcher returns"),
        PENDING_IN_ORDER("after the data fetcher returns, first created first"),
        PENDING_REVERSED("after the data fetcher returns, last created first");

        final String description;

        Timing(String description) {
            this.description = description;
        }
    }

    static final class Case {
        final List<Container> shape;
        final Leaf leaf;
        final Kind kind;
        final Nullability nullability;
        final String className;

        Case(List<Container> shape, Leaf leaf, Kind kind, Nullability nullability, String className) {
            this.shape = shape;
            this.leaf = leaf;
            this.kind = kind;
            this.nullability = nullability;
            this.className = className;
        }

        String javaType() {
            String type = leaf.javaName;
            for (int i = shape.size() - 1; i >= 0; i--) {
                type = shape.get(i).javaName + "<" + type + ">";
            }
            return type;
        }

        String graphQLType() {
            boolean nonNull = nullability == Nullability.NON_NULL;
            String type = leaf.graphQLName + (nonNull ? "!" : "");
            long lists = shape.stream().filter(Container::isList).count();
            for (long i = 0; i < lists; i++) {
                type = "[" + type + "]" + (nonNull ? "!" : "");
            }
            return type;
        }

        String shortJavaType() {
            return javaType().replace("unwrapcheck.Fixtures.", "").replaceAll("[a-z.]+\\.(?=[A-Z])", "");
        }

        String label() {
            return kind.label + " " + shortJavaType();
        }

        String describe() {
            switch (kind) {
                case METHOD:
                    return "public " + shortJavaType() + " field()  for  field: " + graphQLType();
                case PROPERTY:
                    return "public " + shortJavaType() + " field;  for  field: " + graphQLType();
                default:
                    return "extends HashMap<String, " + shortJavaType() + ">  for  Holder.field: " + graphQLType();
            }
        }

        String parentType() {
            return kind == Kind.MAP ? "Holder" : "Query";
        }

        String query() {
            String field = "field" + leaf.selection;
            return "query { " + (kind == Kind.MAP ? "holder { " + field + " }" : field) + " }";
        }
    }

    static final class Plan {
        final Variant variant;
        final List<Plan> children;
        final int cost;

        Plan(Variant variant, List<Plan> children, int cost) {
            this.variant = variant;
            this.children = children;
            this.cost = cost;
        }
    }

    static final class Built {
        final Object returned;
        final Object expected;
        final String text;

        Built(Object returned, Object expected, String text) {
            this.returned = returned;
            this.expected = expected;
            this.text = text;
        }
    }

    static final class Context {
        final Timing timing;
        final String resolverName;
        final List<Runnable> completions = new ArrayList<>();
        final Set<String> failures = new LinkedHashSet<>();
        int nextId = 1;

        Context(Timing timing, String resolverName) {
            this.timing = timing;
            this.resolverName = resolverName;
        }

        CompletableFuture<Object> future(Object value, String failure) {
            if (timing == Timing.DONE) {
                return failure == null ? CompletableFuture.completedFuture(value) : CompletableFuture.failedFuture(new IllegalStateException(failure));
            }
            CompletableFuture<Object> future = new CompletableFuture<>();
            completions.add(() -> {
                if (failure == null) {
                    future.complete(value);
                } else {
                    future.completeExceptionally(new IllegalStateException(failure));
                }
            });
            return future;
        }
    }

    static final class Violation {
        final Case testCase;
        final String value;
        final Timing timing;
        final String expected;
        final String actual;

        Violation(Case testCase, String value, Timing timing, String expected, String actual) {
            this.testCase = testCase;
            this.value = value;
            this.timing = timing;
            this.expected = expected;
            this.actual = actual;
        }
    }

    static final class KnownFinding {
        final int returnTypes;
        final Pattern pattern;
        final String description;
        final Set<String> covered = new LinkedHashSet<>();

        KnownFinding(int returnTypes, Pattern pattern, String description) {
            this.returnTypes = returnTypes;
            this.pattern = pattern;
            this.description = description;
        }
    }

    private static final int DEPTH = 3;
    private static final int BUDGET = 2;
    private static final int PROPERTY_BUDGET = 1;
    private static final String KNOWN_FILE = "src/test/resources/returnvalue/known-findings.txt";
    private static final String BLOCKED = "the data fetcher blocked until they completed";

    private static volatile Object oracleValue;
    private static volatile String oracleFailure;

    private static final ExecutorService fetching = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fetch");
        thread.setDaemon(true);
        return thread;
    });

    private static final SchemaParserOptions options = SchemaParserOptions.newOptions()
        .genericWrappers(
            GenericWrapper.withTransformer(Try.class, 0, (Try t) -> t.get(), type -> type),
            GenericWrapper.listCollectionWithTransformer(Page.class, 0, (Page p) -> p.getContent()),
            GenericWrapper.withCompletionStageTransformer(Later.class, 0, (Later l) -> l.toFuture()))
        .build();

    private static final List<String> mustAccept = List.of(
        "java.util.List<java.util.concurrent.CompletableFuture<unwrapcheck.Fixtures.Payload>> [Payload]",
        "java.util.concurrent.CompletableFuture<java.util.List<unwrapcheck.Fixtures.Try<unwrapcheck.Fixtures.Payload>>> [Payload]",
        "graphql.execution.DataFetcherResult<java.util.List<java.util.concurrent.Future<java.lang.String>>> [String]",
        "java.util.Optional<unwrapcheck.Fixtures.Page<unwrapcheck.Fixtures.Later<java.lang.Object>>> [Item]",
        "java.util.List<graphql.execution.DataFetcherResult<unwrapcheck.Fixtures.Try<java.lang.Object>>> [Item!]!"
    );

    public static boolean check(String[] args, String classpath, PrintStream out) throws Exception {
        int depth = intArg(args, "--depth", DEPTH);
        int budget = intArg(args, "--budget", BUDGET);
        int propertyBudget = intArg(args, "--property-budget", PROPERTY_BUDGET);
        int show = intArg(args, "--show", 15);
        long started = System.nanoTime();

        Set<String> built = Arrays.stream(Container.values()).map(it -> it.javaName).collect(Collectors.toSet());
        List<String> unchecked = Arrays.stream(stringArg(args, "--container-kinds").split(","))
            .filter(it -> !built.contains(it))
            .collect(Collectors.toList());
        if (!unchecked.isEmpty()) {
            out.println("MODEL ERROR: TypeShape.kt has a ContainerKind for these classes, but no Container in Check.java builds them, so a pass would prove nothing about them:");
            unchecked.forEach(it -> out.println("  " + it));
            out.println("Add a Container for each to Check.java with its value variants, and its plain value in build.");
            return false;
        }

        List<Case> cases = cases(depth);
        Map<String, Class<?>> resolvers = compile(cases, classpath);

        int rejected = 0;
        int runs = 0;
        boolean blocked = false;
        Set<String> accepted = new LinkedHashSet<>();
        Set<String> acceptedReturnTypes = new LinkedHashSet<>();
        Map<Case, List<Violation>> violations = new LinkedHashMap<>();
        for (Case testCase : cases) {
            Class<?> generated = resolvers.get(testCase.className);
            Object instance = generated.getDeclaredConstructor().newInstance();
            GraphQLSchema schema;
            try {
                schema = schema(testCase, instance);
            } catch (RuntimeException e) {
                rejected++;
                if (flag(args, "--rejected")) {
                    out.println("rejected " + testCase.describe() + ": " + e.getMessage().lines().findFirst().orElse(""));
                }
                continue;
            }
            accepted.add(testCase.kind.label + " " + testCase.javaType() + " " + testCase.graphQLType());
            acceptedReturnTypes.add(testCase.label());
            GraphQL actual = GraphQL.newGraphQL(schema).build();
            GraphQL oracle = GraphQL.newGraphQL(oracleSchema(schema, testCase.parentType())).build();
            Consumer<Object> returning = returning(testCase, generated, instance);
            List<Timing> timings = testCase.shape.stream().anyMatch(Container::isFuture) && !blocked ? testCase.kind.timings : List.of(Timing.DONE);
            plans:
            for (Plan plan : plansWithinBudget(testCase, 0, testCase.kind == Kind.METHOD ? budget : propertyBudget)) {
                for (Timing timing : timings) {
                    runs++;
                    Violation violation = run(testCase, plan, timing, actual, oracle, returning);
                    if (violation != null) {
                        violations.computeIfAbsent(testCase, c -> new ArrayList<>()).add(violation);
                        if (violation.actual.equals(BLOCKED)) {
                            blocked = true;
                            break plans;
                        }
                    }
                }
            }
        }

        List<String> missing = Arrays.stream(Kind.values())
            .flatMap(kind -> mustAccept.stream()
                .filter(it -> kind.nullabilities.contains(Nullability.NON_NULL) || !it.endsWith("!"))
                .map(it -> kind.label + " " + it))
            .filter(it -> !accepted.contains(it))
            .collect(Collectors.toList());
        out.printf("depth %d, budget %d (%d for properties and Maps): %d return types, %d rejected by the schema build, %d accepted, %d executions, %.1fs%n",
            depth, budget, propertyBudget, cases.size(), rejected, accepted.size(), runs, (System.nanoTime() - started) / 1e9);
        if (blocked) {
            out.println("A data fetcher blocked on a pending future, so later return types ran with completed futures only.");
        }

        Map<String, List<Violation>> failing = new LinkedHashMap<>();
        violations.forEach((testCase, list) -> failing.computeIfAbsent(testCase.label(), it -> new ArrayList<>()).addAll(list));
        List<KnownFinding> known = knownFindings(stringArg(args, "--known"));
        List<List<Violation>> unknown = new ArrayList<>();
        for (String returnType : acceptedReturnTypes) {
            Optional<KnownFinding> finding = known.stream().filter(it -> it.pattern.matcher(returnType).find()).findFirst();
            if (finding.isPresent()) {
                finding.get().covered.add(returnType);
            } else if (failing.containsKey(returnType)) {
                unknown.add(failing.get(returnType));
            }
        }

        for (KnownFinding finding : known) {
            List<String> stillFailing = finding.covered.stream().filter(failing::containsKey).collect(Collectors.toList());
            if (!stillFailing.isEmpty()) {
                out.printf("%nKNOWN (%s): %d return types still fail, e.g.%n", finding.description, stillFailing.size());
                List<Violation> first = failing.get(stillFailing.get(0));
                print(out, first.get(0), first.size());
            }
        }
        boolean passed = true;
        if (!unknown.isEmpty()) {
            int total = unknown.stream().mapToInt(List::size).sum();
            out.printf("%nFAIL: %d executions in %d return types gave graphql-java something other than the value the resolver returned.%n", total, unknown.size());
            unknown.stream().limit(show).forEach(list -> print(out, list.get(0), list.size()));
            passed = false;
        }
        if (!missing.isEmpty() || runs == 0) {
            out.println("\nMODEL ERROR: the schema build rejected return types the check must exercise, so a pass would prove nothing:");
            missing.forEach(it -> out.println("  " + it));
            passed = false;
        }
        if (depth == DEPTH && budget == BUDGET && propertyBudget == PROPERTY_BUDGET) {
            passed &= knownFindingsAreExact(known, failing.keySet(), show, out);
        }
        if (passed) {
            out.println(violations.isEmpty() ? "PASS" : "\nPASS apart from the known findings above");
        }
        return passed;
    }

    private static boolean knownFindingsAreExact(List<KnownFinding> known, Set<String> failing, int show, PrintStream out) {
        List<String> stale = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (KnownFinding finding : known) {
            int covered = finding.covered.size();
            List<String> passing = finding.covered.stream().filter(it -> !failing.contains(it)).collect(Collectors.toList());
            if (passing.size() == covered) {
                stale.add("  " + finding.description + ": none of the " + covered + " return types it matches fail. Delete the line.");
            } else if (!passing.isEmpty()) {
                stale.add("  " + finding.description + ": " + passing.size() + " of the " + covered + " return types it matches pass. Narrow the line to the "
                    + (covered - passing.size()) + " that fail and set its count to that:");
                passing.stream().limit(show).forEach(it -> stale.add("    " + it));
                if (passing.size() > show) {
                    stale.add("    and " + (passing.size() - show) + " more, rerun with --show " + passing.size());
                }
            }
            String counts = "  " + finding.description + ": the line records " + finding.returnTypes + " return types and matches " + covered + " that the schema build accepts.";
            if (covered > finding.returnTypes) {
                added.add(counts);
            } else if (covered < finding.returnTypes) {
                stale.add(counts + " Unless you just narrowed the line, the build now rejects a return type it used to accept. "
                    + "Find it by diffing --rejected output against the last commit, then set the count to " + covered + ".");
            }
        }
        if (!added.isEmpty()) {
            out.println("\nNEW: known findings in " + KNOWN_FILE + " match more return types than they record, so a new failure would print as KNOWN, not FAIL. "
                + "Unless you just widened a line, the build now accepts a return type it used to reject, and that return type fails. "
                + "Find it by diffing --rejected output against the last commit, and treat it as a FAIL:");
            added.forEach(out::println);
        }
        if (!stale.isEmpty()) {
            out.println("\nSTALE: known findings in " + KNOWN_FILE + " no longer match exactly the return types that fail, so a regression in a fixed one would print as KNOWN, not FAIL:");
            stale.forEach(out::println);
        }
        return added.isEmpty() && stale.isEmpty();
    }

    private static List<KnownFinding> knownFindings(String path) throws Exception {
        Pattern format = Pattern.compile("(\\d+) (.+?) # (.+)");
        List<KnownFinding> known = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path))) {
            if (!line.isBlank() && !line.startsWith("#")) {
                Matcher parts = format.matcher(line.trim());
                if (!parts.matches()) {
                    throw new IllegalArgumentException("A known finding is \"<how many return types it matches> <regex over the resolver kind and return type, "
                        + "such as property List<Future<String>>> # <what goes wrong>\", not: " + line);
                }
                known.add(new KnownFinding(Integer.parseInt(parts.group(1)), Pattern.compile(parts.group(2)), parts.group(3)));
            }
        }
        return known;
    }

    private static void print(PrintStream out, Violation v, int count) {
        out.println();
        out.println(v.testCase.describe() + (count > 1 ? "   (" + count + " failing executions, first shown)" : ""));
        out.println("  returns:   " + v.value);
        if (v.testCase.shape.stream().anyMatch(Container::isFuture)) {
            out.println("  futures complete " + v.timing.description);
        }
        out.println("  expected:  " + v.expected);
        out.println("  got:       " + v.actual);
    }

    static List<Case> cases(int depth) {
        List<List<Container>> shapes = new ArrayList<>();
        shapes.add(List.of());
        for (int start = 0, size = 0; size < depth; size++) {
            int end = shapes.size();
            for (int i = start; i < end; i++) {
                for (Container container : Container.values()) {
                    List<Container> shape = new ArrayList<>();
                    shape.add(container);
                    shape.addAll(shapes.get(i));
                    shapes.add(List.copyOf(shape));
                }
            }
            start = end;
        }
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            for (Leaf leaf : Leaf.values()) {
                for (Kind kind : Kind.values()) {
                    String className = "unwrapcheck.gen.R" + i + "_" + leaf.ordinal() + "_" + kind.ordinal();
                    for (Nullability nullability : kind.nullabilities) {
                        cases.add(new Case(shapes.get(i), leaf, kind, nullability, className));
                    }
                }
            }
        }
        return cases;
    }

    static List<Plan> plansWithinBudget(Case testCase, int level, int budget) {
        List<Plan> plans = new ArrayList<>();
        if (level == testCase.shape.size()) {
            plans.add(new Plan(Variant.VALUE, List.of(), 0));
            if (budget > 0) {
                plans.add(new Plan(Variant.NULL, List.of(), 1));
                if (testCase.leaf == Leaf.OBJECT) {
                    plans.add(new Plan(Variant.FUTURE_ITEM, List.of(), 1));
                }
            }
            return plans;
        }
        Container container = testCase.shape.get(level);
        for (Variant variant : container.variants) {
            int cost = variant == Variant.VALUE ? 0 : 1;
            if (cost > budget) {
                continue;
            }
            boolean hasInner = variant == Variant.VALUE || variant == Variant.WITH_ERROR;
            if (!hasInner) {
                plans.add(new Plan(variant, List.of(), cost));
            } else if (container.isList()) {
                for (Plan first : plansWithinBudget(testCase, level + 1, budget - cost)) {
                    for (Plan second : plansWithinBudget(testCase, level + 1, budget - cost - first.cost)) {
                        plans.add(new Plan(variant, List.of(first, second), cost + first.cost + second.cost));
                    }
                }
            } else {
                for (Plan inner : plansWithinBudget(testCase, level + 1, budget - cost)) {
                    plans.add(new Plan(variant, List.of(inner), cost + inner.cost));
                }
            }
        }
        return plans;
    }

    static Built build(Case testCase, Plan plan, int level, Context context) {
        if (level == testCase.shape.size()) {
            if (plan.variant == Variant.NULL) {
                return new Built(null, null, "null");
            }
            String id = "v" + context.nextId++;
            if (plan.variant == Variant.FUTURE_ITEM) {
                return new Built(new FutureItem(id), new FutureItem(id), "FutureItem(" + id + ")");
            }
            if (testCase.leaf == Leaf.STRING) {
                return new Built(id, id, "\"" + id + "\"");
            }
            return new Built(new Payload(id), new Payload(id), "Payload(" + id + ")");
        }
        Container container = testCase.shape.get(level);
        switch (plan.variant) {
            case NULL:
                return new Built(null, null, "null");
            case EMPTY:
                switch (container) {
                    case LIST:
                        return new Built(List.of(), List.of(), "List[]");
                    case PAGE:
                        return new Built(new Page<>(List.of()), List.of(), "Page[]");
                    default:
                        return new Built(Optional.empty(), null, "Optional.empty()");
                }
            case FAILED: {
                String failure = "failed " + container.name().toLowerCase() + " v" + context.nextId++;
                context.failures.add(failure);
                switch (container) {
                    case TRY:
                        return new Built(Try.failure(failure), null, "Try.failure(\"" + failure + "\")");
                    case LATER:
                        return new Built(new Later<>(context.future(null, failure)), null, "Later(failed CompletableFuture \"" + failure + "\")");
                    default:
                        return new Built(context.future(null, failure), null, "failed CompletableFuture(\"" + failure + "\")");
                }
            }
            case FUTURE_TASK: {
                String kind = testCase.kind.resolverKind;
                context.failures.add("Resolver " + kind + " '" + context.resolverName + "' resolved to a " + FutureTask.class.getName() + ", which graphql-java can't wait on. "
                    + "Return a CompletionStage, such as a CompletableFuture, from the " + kind + " or its generic wrapper transformer instead.");
                FutureTask<Object> task = new FutureTask<>(() -> null);
                task.run();
                return new Built(task, null, "FutureTask");
            }
            default:
                break;
        }
        List<Built> inner = new ArrayList<>();
        for (Plan child : plan.children) {
            inner.add(build(testCase, child, level + 1, context));
        }
        Built first = inner.get(0);
        switch (container) {
            case LIST:
            case PAGE: {
                List<Object> returned = inner.stream().map(it -> it.returned).collect(Collectors.toCollection(ArrayList::new));
                List<Object> expected = inner.stream().map(it -> it.expected).collect(Collectors.toCollection(ArrayList::new));
                String text = inner.stream().map(it -> it.text).collect(Collectors.joining(", ", "[", "]"));
                return container == Container.LIST ? new Built(returned, expected, "List" + text) : new Built(new Page<>(returned), expected, "Page" + text);
            }
            case COMPLETABLE_FUTURE:
            case FUTURE:
                return new Built(context.future(first.returned, null), first.expected, "CompletableFuture(" + first.text + ")");
            case LATER:
                return new Built(new Later<>(context.future(first.returned, null)), first.expected, "Later(CompletableFuture(" + first.text + "))");
            case TRY:
                return new Built(Try.success(first.returned), first.expected, "Try.success(" + first.text + ")");
            case OPTIONAL:
                return new Built(Optional.ofNullable(first.returned), first.expected, "Optional.ofNullable(" + first.text + ")");
            default: {
                boolean withError = plan.variant == Variant.WITH_ERROR;
                String error = "error beside " + first.text;
                return new Built(result(first.returned, withError ? error : null), result(first.expected, withError ? error : null),
                    "DataFetcherResult(" + first.text + (withError ? ", error)" : ")"));
            }
        }
    }

    private static DataFetcherResult<Object> result(Object data, String error) {
        DataFetcherResult.Builder<Object> builder = DataFetcherResult.newResult().data(data);
        if (error != null) {
            builder.error(GraphqlErrorBuilder.newError().message(error).build());
        }
        return builder.build();
    }

    static Violation run(Case testCase, Plan plan, Timing timing, GraphQL actual, GraphQL oracle, Consumer<Object> returning) {
        Context context = new Context(timing, testCase.className + ".field");
        Built built = build(testCase, plan, 0, context);
        if (timing == Timing.PENDING_REVERSED) {
            Collections.reverse(context.completions);
        }

        String got;
        String query = testCase.query();
        returning.accept(built.returned);
        java.util.concurrent.Future<CompletableFuture<ExecutionResult>> fetch = fetching.submit(() -> actual.executeAsync(ExecutionInput.newExecutionInput().query(query).build()));
        CompletableFuture<ExecutionResult> result;
        try {
            try {
                result = fetch.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                context.completions.forEach(Runnable::run);
                return new Violation(testCase, built.text, timing, "the data fetcher returns before its futures complete", BLOCKED);
            }
            context.completions.forEach(Runnable::run);
            got = result.get(2, TimeUnit.SECONDS).toSpecification().toString();
        } catch (TimeoutException e) {
            return new Violation(testCase, built.text, timing, "a result once every future completed", "no result after 2 seconds");
        } catch (Exception e) {
            return new Violation(testCase, built.text, timing, "a result", "execute threw " + e);
        }

        List<String> acceptable = new ArrayList<>();
        if (context.failures.isEmpty()) {
            oracleValue = built.expected;
            oracleFailure = null;
            acceptable.add(oracle.execute(query).toSpecification().toString());
        } else {
            for (String failure : context.failures) {
                oracleValue = null;
                oracleFailure = failure;
                acceptable.add(oracle.execute(query).toSpecification().toString());
            }
        }
        return acceptable.contains(got) ? null : new Violation(testCase, built.text, timing, String.join("\n    or     ", acceptable), got);
    }

    static Consumer<Object> returning(Case testCase, Class<?> generated, Object instance) throws Exception {
        switch (testCase.kind) {
            case METHOD:
                return value -> Fixtures.nextReturnValue = value;
            case PROPERTY: {
                Field field = generated.getField("field");
                return value -> {
                    try {
                        field.set(instance, value);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                };
            }
            default:
                return value -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> holder = (Map<String, Object>) instance;
                    holder.put("field", value);
                    Fixtures.nextReturnValue = holder;
                };
        }
    }

    static GraphQLSchema schema(Case testCase, Object generated) {
        SchemaParserBuilder parser = SchemaParser.newParser().options(options);
        String sdl;
        if (testCase.kind == Kind.MAP) {
            sdl = "type Query { holder: Holder }\ntype Holder { field: " + testCase.graphQLType() + " }\n";
            parser.resolvers(new Fixtures.HolderQuery()).dictionary("Holder", generated.getClass());
        } else {
            sdl = "type Query { field: " + testCase.graphQLType() + " }\n";
            parser.resolvers((GraphQLResolver<?>) generated);
        }
        if (testCase.leaf != Leaf.STRING) {
            sdl += "type Payload { data: String }\n";
        }
        if (testCase.leaf == Leaf.OBJECT) {
            sdl += "type FutureItem { data: String }\nunion Item = Payload | FutureItem\n";
            parser.dictionary(Payload.class, FutureItem.class);
        }
        return parser.schemaString(sdl).build().makeExecutableSchema();
    }

    static GraphQLSchema oracleSchema(GraphQLSchema schema, String parentType) {
        DataFetcher<Object> plain = environment -> {
            if (oracleFailure != null) {
                throw new IllegalStateException(oracleFailure);
            }
            return oracleValue;
        };
        return schema.transform(builder -> builder.codeRegistry(
            schema.getCodeRegistry().transform(registry -> registry.dataFetcher(FieldCoordinates.coordinates(parentType, "field"), plain))));
    }

    static Map<String, Class<?>> compile(List<Case> cases, String classpath) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Case testCase : cases) {
            String simpleName = testCase.className.substring(testCase.className.lastIndexOf('.') + 1);
            String type = testCase.javaType();
            String body;
            switch (testCase.kind) {
                case METHOD:
                    body = " implements graphql.kickstart.tools.GraphQLQueryResolver {\n"
                        + "    @SuppressWarnings(\"unchecked\")\n"
                        + "    public " + type + " field() { return (" + type + ") unwrapcheck.Fixtures.nextReturnValue; }\n";
                    break;
                case PROPERTY:
                    body = " implements graphql.kickstart.tools.GraphQLQueryResolver {\n"
                        + "    public " + type + " field;\n";
                    break;
                default:
                    body = " extends java.util.HashMap<String, " + type + "> {\n";
                    break;
            }
            sources.putIfAbsent(testCase.className, "package unwrapcheck.gen;\npublic class " + simpleName + body + "}\n");
        }

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> output = new HashMap<>();
        StandardJavaFileManager files = javac.getStandardFileManager(null, null, null);
        ForwardingJavaFileManager<StandardJavaFileManager> memory = new ForwardingJavaFileManager<>(files) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return output.computeIfAbsent(className, name -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        List<JavaFileObject> units = sources.entrySet().stream().map(source ->
            new SimpleJavaFileObject(URI.create("string:///" + source.getKey().replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source.getValue();
                }
            }).collect(Collectors.toList());
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> javacOptions = List.of("-classpath", classpath, "-nowarn", "-proc:none");
        if (!javac.getTask(null, memory, diagnostics, javacOptions, null, units).call()) {
            throw new IllegalStateException("generated resolvers don't compile: " + diagnostics.getDiagnostics());
        }

        ClassLoader loader = new ClassLoader(Check.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream bytes = output.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes.toByteArray(), 0, bytes.size());
            }
        };
        Map<String, Class<?>> classes = new HashMap<>();
        for (String name : sources.keySet()) {
            classes.put(name, loader.loadClass(name));
        }
        return classes;
    }

    private static String stringArg(String[] args, String name) {
        int i = List.of(args).indexOf(name);
        return i >= 0 && i < args.length - 1 ? args[i + 1] : null;
    }

    private static boolean flag(String[] args, String name) {
        return List.of(args).contains(name);
    }

    private static int intArg(String[] args, String name, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return fallback;
    }
}
