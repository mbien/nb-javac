# AGENTS.md

nb-javac is a backport of OpenJDK `javac` to bytecode 21, for Apache NetBeans'
Java editor integration. It compiles the *pinned JDK's own* compiler sources
with that JDK's javac at `--release 21`. See `README.md` for the user-facing
overview.

## Orientation — the layout is counterintuitive

- **The compiler sources are NOT in this repo's `src/`.** They live in
  `jdk/src/jdk.compiler/` — `jdk/` is a git *clone* of the pinned OpenJDK
  (`jdk.git.commit` in `jdk.properties`), git-ignored. Never edit or commit it.
- `src/` is small and project-owned: the Jackpot hints
  (`META-INF/upgrade/nbjavac.hint`), `META-INF/services` entries, and two
  wrapper classes (`nbjavac/VMWrapper.java`, `JmodFileWrapper.java`).
- `jdk-src/` is *generated*: the Jackpot-processed compiler sources
  (git-ignored). Never hand-edit it — changes to the compiler source go into
  the Jackpot hint file or `patches/jdk-src-release-21.patch`.
- `test/` is the backported javac test suite. `nbjavac/NBJavacBuild.java`
  is the entire build (rife bld).
- `lib/*` jars are fetched by the build; only the bld wrapper is tracked.
- `old/` is an untracked scratch area — don't touch it.

## Build

```
./bld jdk    # first time only: clone + build the pinned JDK (slow, ~12 m)
./bld jar    # -> dist/nb-javac-<jdk-tag>.jar / -api.jar
./bld test   # builds the jars, runs the suite in the current JVM
```

The download of the previous nb-javac release (`nb-javac-jdk-21u.jar`,
fixed Maven Central URL) is still load-bearing in two places:
- *compile*: `com.sun.tools.javac.model.AnnotationProxyMaker` references
  `sun.reflect.annotation` internals, which the hint renames to
  `nbjavac.sun.annotation` — those classes come from the old jar and sit on
  the compile classpath;
- *runtime*: the impl jar ships the old jar's `com.sun.tools.classfile`
  classes, which NetBeans loads for `classfile/CodeGenerator` —
  apache/netbeans#9368 tracks retiring them (`com.sun.tools.classfile`
  vanished from the JDK in 25, re-landed as public `java.lang.classfile`,
  JEP 484).

Host JDK: 21+ for `jar`/`test`. Building the pinned JDK image additionally
requires a boot JDK the pinned release accepts (the workflow names one). The
JDK build is gated on the image existing
under `jdk/build/*/images/jdk`, so a rerun of `./bld jdk` is a no-op on a hit.

## Pins and the CI cache

`jdk.properties` pins the JDK. The CI cache key for the JDK build is the hash
of three files: `jdk.properties`, `patches/jdk-src-release-21.patch`, and
`src/META-INF/upgrade/nbjavac.hint`. Changing any of them invalidates the
cached build — a CI miss means a full JDK rebuild, so don't edit these
casually (e.g. to fix whitespace).

## CI — `.github/workflows/main.yml`

One workflow, two jobs:

- `base` (the cache **writer**): builds the JDK, builds the jars, uploads
  `dist/`, then runs the test suite on JDK 21, 25 and 28-ea.
- `netbeans` (cache **reader only**, `actions/cache/restore`): rebuilds the
  jars, builds NetBeans `release310` with the jar, runs commit-validation
  under Xvfb. It must keep `needs: base` so the writer runs first.

## Conventions

- The human does all committing; leave changes uncommitted.
- Short commit messages.
- `patches/` holds temporary git patches; keep their context in sync when the
  JDK target changes.
