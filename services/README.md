# services

The platform's modules. **This code is ours.** It follows xenopsbase-stemcell's conventions
deliberately — same Spring Boot, same Java, same plugins, same rules — because this platform is
intended to run on that infrastructure eventually, and two codebases with different shapes would
make that a migration rather than a deployment.

## Building

Java 25. `JAVA_HOME` must point at a JDK 25 — and on most machines it does not.

```bash
make java-home     # what the build needs, what JAVA_HOME says, what will actually be used
make build
make test
make run S=identity
```

`make` targets resolve the JDK themselves. Running `./mvnw` or `java -jar` directly does not, and
this machine is a worked example of why: a Java 8 JRE first on `PATH`, `JAVA_HOME` on 21, and the
25 the build needs installed but unreferenced. `JAVA_HOME` does not decide which `java` binary
runs, so `java -jar` picked 8 and failed with

```
UnsupportedClassVersionError: ... class file version 61.0 ... only recognizes ... up to 52.0
```

which names neither JDK, nor the correct one sitting on the same disk.

## Layout

```
services/
├── pom.xml                  the parent — versions, plugins, conventions, declared once
├── platform-common/         what every module shares, on ANY stack. No domain code, ever
├── platform-common-web/     the servlet half: filters and controllers
├── identity/                the first module
├── streaming/
├── reporting/
└── catalog/
```

**Why the shared code is two modules.** `platform-common` used to depend on
`spring-boot-starter-web`, so every class in it could reach for `HttpServletRequest` and six did. A
shared library that depends on one web stack is a library only services on that stack can use, and it
decides for a future gateway — which runs on WebFlux, where none of those filters exist — before
anyone has had the argument. Each pom bans the stack it must not have, so the split is enforced
rather than described; a class that needs the servlet API fails the build in `platform-common` with a
message naming the module it belongs in.

Both keep the `com.xenopsoftware.learn.common` package root, which is why every service's
`@ComponentScan` is unchanged: a package may span two jars.

This no longer differs from the stemcell. It used to — that repository gave each of its two services
an independent pom, and this README said so. Its ADR-0017 adopted a parent pom and the same
neutral/servlet split, so the two are now the same shape, which is the point: this repository is
meant to be forked into that one.

## Package convention

```
com.xenopsoftware.learn.<module>
├── config/       Spring configuration and @ConfigurationProperties
├── domain/       JPA entities
├── repository/   Spring Data repositories
├── security/     authorities, token handling
└── web/
    └── rest/     controllers
        └── errors/  RFC 7807 translation
```

Rules that are not obvious from the tree, and are enforced by `TechnicalStructureTest` rather than
by review:

- **Controllers live under `web/rest`, never at the package root.** Everything under `/api/**` is
  authenticated by `SecurityConfiguration`; a controller outside that tree has its exposure decided
  by where somebody put the file.
- **`platform-common` may not depend on any module.** The moment it does, two modules are coupled
  through a third, which is harder to see than coupling them directly.
- **Only `TenantFilter` binds the tenant.** It comes from a verified claim, never a header, a query
  parameter or a body. The version of this that gets added is reasonable-looking — a header for
  testing, a parameter for support — so it is a build failure rather than a review comment.
- **Entities are only created alongside a Flyway migration, and the migration is written first.**
  `ddl-auto: validate` enforces it: an entity that disagrees with its migration refuses to start.
- **No module reads another module's database.** Enforced by credentials in the local stack: a
  cross-module query fails to connect rather than returning the wrong answer.

## Databases

One per module, one role each (`docs/local-stack.md`). Whether `identity`, `catalog` and
`assessment` run as one process or three is still open (ADR-0109). The data boundary is not open,
and it is drawn now because drawing it later means separating schemas that a dozen queries already
join across.

## What is not here yet

The template is real but incomplete. Still owed by T-9.10: the shared error shape, structured
logging with a request id, the OpenAPI snapshot gate, and a test harness that starts a service
against real dependencies. Each arrives with the first module that needs it, rather than being
written speculatively.
