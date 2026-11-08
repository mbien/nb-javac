_nb-javac_ is a backport of OpenJDK `javac` to Java bytecode 21. The primary
consumer is Apache NetBeans, which uses it for its Java editor integration.

This project builds a pinned JDK (see `jdk.properties`), compiles the compiler
from that JDK's sources with its own javac at `--release 21`, and splits the
result into api/impl jars — 1:1 identical to the official `javac` of that JDK, but
runnable on any JDK 21+.

## Building

Prerequisites: Git and a JDK 21+ on `PATH` (the bld wrapper fetches its own
tooling).

```
$ ./bld jdk    # first time only: clone + build the pinned JDK
$ ./bld jar    # -> dist/nb-javac-<jdk-tag>-api.jar / -impl.jar
$ ./bld test   # builds the jars, then runs the test suite
```

The JDK build is cached under `jdk/build/` — a rerun of `./bld jdk` is a
no-op once the image exists. The JDK version is pinned in `jdk.properties`;
`patches/jdk-src-release-21.patch` makes the pinned sources compile at
`--release 21`. Jackpot output lands in `jdk-src/`, build products in
`build/` and `dist/` (all git-ignored).

## History

`nb-javac` originated as a NetBeans-specific fork of OpenJDK's javac, with patches
supporting IDE features such as error recovery and cancellability. Over time,
it converged again to the steadily improving upstream javac and is now
purely a backport, mostly driven by automated Jackpot code transformation scripts.
