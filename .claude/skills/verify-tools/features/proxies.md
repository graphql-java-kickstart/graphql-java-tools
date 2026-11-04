# Proxied resolvers

A resolver wrapped in a Spring AOP proxy is scanned as its target class through `Spring4AopProxyHandler`. A CGLIB proxy
(`proxyTargetClass = true`) subclasses the target, so its methods work. A JDK dynamic proxy only implements the target's
interfaces, so every query of a field that resolves to the target's own method or field fails, and the build logs a
warning per field that suggests class-based proxies, plus a getter for a field.

## Sub-features

- `proxy-jdk-warning` logs a WARN per field that a JDK proxy can't call, naming the type, field, member and proxy class.
- `proxy-jdk-field-getter` suggests adding a getter when the field resolves to a public field.
- `proxy-jdk-query` still fails each such field at query time.
- `proxy-cglib` serves methods and getters through a CGLIB proxy with no warning, but a public field reads `0`.
- `proxy-plain` serves the same resolver without a proxy and with no warning.

## Expected results

- **JDK proxy.** `run jdk-proxy`. Exit `0`. `probe.log` has three
  `SchemaClassScanner` WARNs starting `Field 'Query.test'`, `Field 'Query.count'` and `Field 'Query.total'`. The
  `count` one ends `Add a getter for the field and use class-based proxies, e.g. proxyTargetClass = true in Spring.`, the
  other two end `Use class-based proxies instead, e.g. proxyTargetClass = true in Spring.` `01-query.json` has
  `data = {"test": null, "count": null, "total": null}` and three errors, `object of type jdk.proxy2.$ProxyN is not an
  instance of scenarios.ProxiedQuery` for `test` and `total`, and `Can not get final int field scenarios.ProxiedQuery.count`
  for `count`.
- **CGLIB proxy.** `run jdk-proxy-cglib`. Exit `0`, empty `probe.log`,
  `01-query.json` has `data = {"test": "ok", "count": 0, "total": 2}`.
- **No proxy.** `run jdk-proxy-plain`. Exit `0`, empty `probe.log`,
  `01-query.json` has `data = {"test": "ok", "count": 1, "total": 2}`.

## Gotchas

- The warning shipped with #853. On a checkout without it, `jdk-proxy` has the same query errors and an empty `probe.log`.
- The proxy number in `jdk.proxy2.$ProxyN` varies between runs.
- CGLIB can't override `final` members, so the resolver and its members must be `open`. A final getter is called on the
  proxy itself and reads the proxy's empty field, like `count` does.
- The three scenarios share the schema and operations in `compat/scenarios/jdk-proxy/`.
