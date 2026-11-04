# Proxied resolvers

A resolver wrapped in a Spring AOP proxy is scanned as its target class through `Spring4AopProxyHandler`. A CGLIB proxy
(`proxyTargetClass = true`) subclasses the target, so its methods work. A JDK dynamic proxy only implements the target's
interfaces, so it can't call a field that resolves to the target's own method or field. The build fails with a
`FieldResolverError` that names every such field and suggests class-based proxies, plus a getter for a field.

## Sub-features

- `proxy-jdk-fails` fails the build with one line per field that a JDK proxy can't call, naming the type, field, member
  and proxy class, even for fields no operation queries.
- `proxy-jdk-field-getter` suggests adding a getter for a field that resolves to a public field.
- `proxy-cglib` serves methods and getters through a CGLIB proxy with no warning, but a public field reads `0`.
- `proxy-plain` serves the same resolver without a proxy and with no warning.

## Expected results

- **JDK proxy.** `run jdk-proxy`. Exit `2`, no operations run, and `probe.log` is empty. `build-error.txt` starts with
  `graphql.kickstart.tools.resolver.FieldResolverError: Field 'Query.test' resolves to
  graphql.kickstart.tools.compat.scenarios.ProxiedQuery.test, but the resolver is a jdk.proxy2.$ProxyN, which isn't a
  graphql.kickstart.tools.compat.scenarios.ProxiedQuery, so every query of the field will fail.`, followed by the same
  line for `Field 'Query.count'` (`ProxiedQuery.count`) and `Field 'Query.total'` (`ProxiedQuery.getTotal`), and ends
  `This happens with JDK dynamic proxies, which only implement interfaces. Use class-based proxies instead, e.g.
  proxyTargetClass = true in Spring. For a public field, also add a getter, since class-based proxies don't pass field
  reads to the target.`
- **CGLIB proxy.** `run jdk-proxy-cglib`. Exit `0`, empty `probe.log`,
  `01-query.json` has `data = {"test": "ok", "count": 0, "total": 2}`.
- **No proxy.** `run jdk-proxy-plain`. Exit `0`, empty `probe.log`,
  `01-query.json` has `data = {"test": "ok", "count": 1, "total": 2}`.

## Gotchas

- Before #913 (commit f4800425) `jdk-proxy` builds with exit `0`. `01-query.json` has
  `data = {"test": null, "count": null, "total": null}` and three errors, `object of type jdk.proxy2.$ProxyN is not an
  instance of graphql.kickstart.tools.compat.scenarios.ProxiedQuery` for `test` and `total`, and `Can not get final int
  field graphql.kickstart.tools.compat.scenarios.ProxiedQuery.count` for `count`. From #853 on, `probe.log` has one `SchemaClassScanner` WARN per field. Before it, `probe.log` is empty.
- The proxy number in `jdk.proxy2.$ProxyN` varies between runs.
- CGLIB can't override `final` members, so the resolver and its members must be `open`. A final getter is called on the
  proxy itself and reads the proxy's empty field, like `count` does.
- The three scenarios share the schema and operations in `compat/scenarios/jdk-proxy/`.
