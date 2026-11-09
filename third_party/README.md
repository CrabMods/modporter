# Third-party JVM notices

This inventory covers the JVM code and native libraries merged into the
ModPorter fat JAR. It is based on the runtime dependencies declared for
ModPorter 0.4.0 and was cross-checked against its resolved runtime graph,
bundled Maven metadata, and class bytes in `build/libs/modporter-0.4.0-all.jar`.
The package must be checked again if the resolved runtime graph changes.

The files in `jvm/` preserve the license choices, copyright notices, and
upstream NOTICE text needed by the bundled components. ModPorter's
Apache-2.0 license does not replace any of these terms.

## Selected alternatives

The following upstream projects offer multiple licenses. This distribution
uses one stated alternative for the bundled copy:

- JavaParser 3.26.4: Apache License 2.0.
- Javassist 3.30.2-GA: Apache License 2.0.
- JNA 5.13.0: Apache License 2.0. JNA's bundled libffi 3.4.4 remains under
  the libffi MIT-style license.
- Logback 1.4.14: Eclipse Public License 1.0.

The original multiple-license declarations are retained in
`jvm/JavaParser-dual-license.txt`, `jvm/Javassist-multi-license.html`,
`jvm/JNA-dual-license.txt`, and `jvm/Logback-dual-license.txt`.

Source code corresponding to the bundled Logback 1.4.14 components is
available from the upstream `v_1.4.14` tag:
https://github.com/qos-ch/logback/tree/v_1.4.14

## Packaged components

| Maven component | Version | License used | Bundled text |
| --- | --- | --- | --- |
| `com.github.ajalt.clikt:clikt-jvm` | 4.2.2 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `com.github.ajalt.mordant:mordant-jvm` | 2.2.0 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `com.github.ajalt.colormath:colormath-jvm` | 3.3.1 | MIT | `jvm/Colormath-MIT.txt` |
| `org.jetbrains:markdown-jvm` | 0.5.2 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `it.unimi.dsi:fastutil-core` | 8.5.12 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `net.java.dev.jna:jna` | 5.13.0 | Apache-2.0 alternative | `jvm/JNA-dual-license.txt`, `jvm/JNA-Apache-2.0.txt` |
| libffi native code embedded by JNA | 3.4.4 | MIT-style libffi license | `jvm/JNA-libffi-MIT.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib` | 1.9.22 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7`, `kotlin-stdlib-jdk8` | 1.9.10; compatibility metadata | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.7.3 | Apache-2.0 | `jvm/Apache-2.0.txt`, `jvm/kotlinx-coroutines-NOTICE.txt` |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.6.2 | Apache-2.0 | `jvm/Apache-2.0.txt`, `jvm/kotlinx-serialization-NOTICE.txt` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.6.2 | Apache-2.0 | `jvm/Apache-2.0.txt`, `jvm/kotlinx-serialization-NOTICE.txt` |
| `io.github.microutils:kotlin-logging-jvm` | 3.0.5 | Apache-2.0 | `jvm/kotlin-logging-LICENSE.txt` |
| `ch.qos.logback:logback-classic` | 1.4.14 | EPL-1.0 alternative | `jvm/Logback-dual-license.txt`, `jvm/EPL-1.0.html` |
| `ch.qos.logback:logback-core` | 1.4.14 | EPL-1.0 alternative | `jvm/Logback-dual-license.txt`, `jvm/EPL-1.0.html` |
| `org.slf4j:slf4j-api` | 2.0.7 | MIT | `jvm/SLF4J-MIT.txt` |
| `com.github.javaparser:javaparser-core` | 3.26.4 | Apache-2.0 alternative | `jvm/JavaParser-dual-license.txt`, `jvm/JavaParser-Apache-2.0.txt` |
| `com.github.javaparser:javaparser-symbol-solver-core` | 3.26.4 | Apache-2.0 alternative | `jvm/JavaParser-dual-license.txt`, `jvm/JavaParser-Apache-2.0.txt` |
| `org.javassist:javassist` | 3.30.2-GA | Apache-2.0 alternative | `jvm/Javassist-multi-license.html`, `jvm/Apache-2.0.txt` |
| `com.google.guava:guava` | 33.4.0-jre | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `com.google.guava:failureaccess` | 1.0.2 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `com.google.guava:listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0; metadata-only compatibility artifact | `jvm/Apache-2.0.txt` |
| `org.checkerframework:checker-qual` | 3.43.0 | MIT | `jvm/Checker-Qual-MIT.txt` |
| `com.google.code.findbugs:jsr305` | 3.0.2 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `com.google.errorprone:error_prone_annotations` | 2.36.0 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `com.google.j2objc:j2objc-annotations` | 3.0.0 | Apache-2.0 | `jvm/Apache-2.0.txt` |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | `jvm/Apache-2.0.txt` |

## Pinned upstream evidence

- Clikt 4.2.2:
  https://github.com/ajalt/clikt/blob/4.2.2/LICENSE.txt
- Mordant 2.2.0:
  https://github.com/ajalt/mordant/blob/2.2.0/LICENSE.txt
- Colormath 3.3.1:
  https://github.com/ajalt/colormath/blob/3.3.1/LICENSE
- JetBrains Markdown 0.5.2 artifact POM:
  https://repo1.maven.org/maven2/org/jetbrains/markdown-jvm/0.5.2/markdown-jvm-0.5.2.pom
- fastutil 8.5.12:
  https://github.com/vigna/fastutil/blob/8.5.12/LICENSE-2.0
- JNA 5.13.0 and its libffi source:
  https://github.com/java-native-access/jna/tree/5.13.0
- Kotlin 1.9.22:
  https://github.com/JetBrains/kotlin/tree/v1.9.22/license
- kotlinx.coroutines 1.7.3:
  https://github.com/Kotlin/kotlinx.coroutines/tree/1.7.3
- kotlinx.serialization 1.6.2:
  https://github.com/Kotlin/kotlinx.serialization/tree/v1.6.2
- kotlin-logging 3.0.5:
  https://github.com/oshai/kotlin-logging/blob/3.0.5/LICENSE
- Logback 1.4.14:
  https://github.com/qos-ch/logback/blob/v_1.4.14/LICENSE.txt
- SLF4J 2.0.7:
  https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar
  (`META-INF/LICENSE.txt` is preserved because its 2023 copyright year differs
  from the repository tag's license file)
- JavaParser 3.26.4:
  https://github.com/javaparser/javaparser/tree/javaparser-parent-3.26.4
- Javassist 3.30.2-GA:
  https://github.com/jboss-javassist/javassist/blob/rel_3_30_2_ga/License.html
- Guava 33.4.0 and its bundled compatibility/annotation dependencies:
  https://github.com/google/guava/tree/v33.4.0
- Checker Qual 3.43.0:
  https://github.com/typetools/checker-framework/blob/checker-framework-3.43.0/checker-qual/LICENSE.txt
- JSR-305 3.0.2 artifact POM:
  https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom
- Error Prone 2.36.0:
  https://github.com/google/error-prone/blob/v2.36.0/COPYING
- J2ObjC annotations 3.0.0:
  https://github.com/google/j2objc/blob/3.0.0/LICENSE
- JetBrains annotations 23.0.0:
  https://github.com/JetBrains/java-annotations/blob/23.0.0/LICENSE.txt

This inventory records upstream terms and the selected alternatives; it is not
a legal opinion. A release should not proceed if its final fat JAR contains
additional runtime components that are absent from this list.
