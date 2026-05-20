# Third Party Licenses (Server Bundles)

This document records license information for third-party software
redistributed under `bundles/`. Each section corresponds to one bundle
directory and lists the upstream project, the version pinned in
`build.properties`, the primary license, and the licenses of the JARs
shipped under `lib/`.

When a single OSGi bundle re-exports multiple JAR files, every JAR is
listed individually. JARs declared in `bin.excludes` are kept in the
source tree for development convenience but are **not** included in the
distributed OSGi bundle; they are noted as such.

Full license texts are available from the upstream projects linked
below. Where a JAR's NOTICE file applies, refer to the upstream
distribution for attribution requirements.

---

## bundles/org.apache.camel

- **Upstream project:** [Apache Camel](https://camel.apache.org/) 4.18.1
- **Primary license:** Apache License 2.0
- **Upstream LICENSE / NOTICE:**
  - <https://github.com/apache/camel/blob/main/LICENSE.txt>
  - <https://github.com/apache/camel/blob/main/NOTICE.txt>

| JAR | Project | License |
| --- | --- | --- |
| `camel-api-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-base-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-base-engine-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-bean-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-cluster-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-core-engine-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-core-languages-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-core-model-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-core-processor-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-core-reifier-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-direct-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-dsl-support-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-file-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-java-joor-dsl-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-joor-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-jsonpath-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-management-api-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-seda-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-support-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-timer-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-util-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-util-json-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xml-io-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xml-io-dsl-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xml-io-util-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xml-jaxp-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xml-jaxp-util-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xpath-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-xslt-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-yaml-dsl-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-yaml-dsl-common-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `camel-yaml-dsl-deserializers-4.18.1.jar` | Apache Camel | Apache License 2.0 |
| `accessors-smart-2.6.0.jar` | [Netplex json-smart](https://github.com/netplex/json-smart-v2) | Apache License 2.0 |
| `asm-9.7.1.jar` | [OW2 ASM](https://asm.ow2.io/) | BSD 3-Clause |
| `jackson-annotations-2.19.4.jar` | [FasterXML Jackson](https://github.com/FasterXML/jackson) | Apache License 2.0 |
| `jackson-core-2.19.4.jar` | FasterXML Jackson | Apache License 2.0 |
| `jackson-databind-2.19.4.jar` | FasterXML Jackson | Apache License 2.0 |
| `jakarta.activation-api-2.1.4.jar` | [Jakarta Activation](https://github.com/jakartaee/jaf-api) | Eclipse Distribution License 1.0 (BSD 3-Clause) |
| `jakarta.xml.bind-api-4.0.5.jar` | [Jakarta XML Binding](https://github.com/jakartaee/jaxb-api) | Eclipse Distribution License 1.0 (BSD 3-Clause) |
| `joor-0.9.15.jar` | [jOOR](https://github.com/jOOQ/jOOR) | Apache License 2.0 |
| `json-path-2.10.0.jar` | [Jayway JsonPath](https://github.com/json-path/JsonPath) | Apache License 2.0 |
| `json-smart-2.6.0.jar` | Netplex json-smart | Apache License 2.0 |
| `snakeyaml-engine-3.0.1.jar` | [SnakeYAML Engine](https://bitbucket.org/snakeyaml/snakeyaml-engine) | Apache License 2.0 |

---

## bundles/org.apache.groovy

- **Upstream project:** [Apache Groovy](https://groovy.apache.org/) 4.0.25
- **Primary license:** Apache License 2.0
- **Upstream LICENSE / NOTICE:**
  - <https://github.com/apache/groovy/blob/master/LICENSE>
  - <https://github.com/apache/groovy/blob/master/NOTICE>

| JAR | Project | License |
| --- | --- | --- |
| `groovy-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-ant-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-astbuilder-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-cli-commons-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-cli-picocli-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-console-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-contracts-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-datetime-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-dateutil-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-docgenerator-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-ginq-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-groovydoc-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-groovysh-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-jmx-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-json-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-jsr223-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-macro-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-macro-library-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-nio-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-servlet-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-sql-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-swing-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-templates-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-test-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-test-junit5-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-testng-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-toml-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-typecheckers-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-xml-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy-yaml-4.0.25.jar` | Apache Groovy | Apache License 2.0 |
| `groovy.icns` | Apache Groovy (icon asset) | Apache License 2.0 |
| `ant-1.10.15.jar` | [Apache Ant](https://ant.apache.org/) | Apache License 2.0 |
| `ant-antlr-1.10.15.jar` | Apache Ant | Apache License 2.0 |
| `ant-junit-1.10.15.jar` | Apache Ant | Apache License 2.0 |
| `ant-launcher-1.10.15.jar` | Apache Ant | Apache License 2.0 |
| `commons-cli-1.6.0.jar` | [Apache Commons CLI](https://commons.apache.org/proper/commons-cli/) | Apache License 2.0 |
| `gpars-1.2.1.jar` | [GPars](http://gpars.org/) | Apache License 2.0 |
| `hamcrest-core-1.3.jar` | [Hamcrest](https://hamcrest.org/) | BSD 3-Clause |
| `ivy-2.5.3.jar` | [Apache Ivy](https://ant.apache.org/ivy/) | Apache License 2.0 |
| `jackson-annotations-2.18.2.jar` | FasterXML Jackson | Apache License 2.0 |
| `jackson-core-2.18.2.jar` | FasterXML Jackson | Apache License 2.0 |
| `jackson-databind-2.18.2.jar` | FasterXML Jackson | Apache License 2.0 |
| `jackson-dataformat-toml-2.18.2.jar` | FasterXML Jackson | Apache License 2.0 |
| `jackson-dataformat-yaml-2.18.2.jar` | FasterXML Jackson | Apache License 2.0 |
| `jansi-2.4.1.jar` | [Jansi](https://github.com/fusesource/jansi) | Apache License 2.0 |
| `javaparser-core-3.26.3.jar` | [JavaParser](https://github.com/javaparser/javaparser) | Dual: Apache License 2.0 / LGPL 3.0 |
| `jcommander-1.78.jar` | [JCommander](https://jcommander.org/) | Apache License 2.0 |
| `jline-2.14.6.jar` | [JLine 2](https://github.com/jline/jline2) | BSD 3-Clause |
| `jquery-3.5.1.jar` | [jQuery](https://jquery.com/) (bundled for groovydoc) | MIT License |
| `jsr166y-1.7.0.jar` | JSR 166 (Doug Lea) | Public Domain (CC0) |
| `junit-4.13.2.jar` | [JUnit 4](https://junit.org/junit4/) | Eclipse Public License 1.0 |
| `junit-jupiter-api-5.11.4.jar` | [JUnit 5](https://junit.org/junit5/) | Eclipse Public License 2.0 |
| `junit-jupiter-engine-5.11.4.jar` | JUnit 5 | Eclipse Public License 2.0 |
| `junit-platform-commons-1.11.4.jar` | JUnit 5 | Eclipse Public License 2.0 |
| `junit-platform-engine-1.11.4.jar` | JUnit 5 | Eclipse Public License 2.0 |
| `junit-platform-launcher-1.11.4.jar` | JUnit 5 | Eclipse Public License 2.0 |
| `multiverse-core-0.7.0.jar` | [Multiverse STM](https://github.com/pveentjer/Multiverse) | Apache License 2.0 |
| `mxparser-1.2.2.jar` | [MXParser](https://github.com/x-stream/mxparser) | Indiana University Extreme! Lab Software License 1.1.1 (BSD-style) |
| `opentest4j-1.3.0.jar` | [OpenTest4J](https://github.com/ota4j-team/opentest4j) | Apache License 2.0 |
| `org.abego.treelayout.core-1.0.3.jar` | [abego TreeLayout](http://treelayout.sourceforge.net/) | BSD 3-Clause |
| `qdox-1.12.1.jar` | [QDox](https://github.com/codehaus/qdox) | Apache License 2.0 |
| `snakeyaml-2.3.jar` | [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) | Apache License 2.0 |
| `testng-7.5.1.jar` | [TestNG](https://testng.org/) | Apache License 2.0 |
| `xstream-1.4.21.jar` | [XStream](https://x-stream.github.io/) | BSD 3-Clause |

---

## bundles/org.apache.tika

- **Upstream project:** [Apache Tika](https://tika.apache.org/) 2.6.0
- **Primary license:** Apache License 2.0
- **Upstream LICENSE / NOTICE:**
  - <https://github.com/apache/tika/blob/main/LICENSE.txt>
  - <https://github.com/apache/tika/blob/main/NOTICE.txt>

| JAR | Project | License |
| --- | --- | --- |
| `tika-app-2.6.0.jar` | Apache Tika (uber-jar) | Apache License 2.0 |

`tika-app` is a fat JAR that re-bundles a large number of transitive
dependencies. The complete list of embedded third-party components and
their respective licenses is enumerated in the LICENSE and NOTICE files
included inside the JAR (and mirrored at the upstream LICENSE / NOTICE
links above). Notable categories include:

- Apache Commons (compress, codec, io, lang3, ...): Apache License 2.0
- Apache POI: Apache License 2.0
- PDFBox, FontBox: Apache License 2.0
- Jackson (annotations / core / databind): Apache License 2.0
- Jsoup: MIT License
- Bouncy Castle: Bouncy Castle License (MIT-style)
- ICU4J: ICU License (Unicode-3.0 compatible)
- ASM: BSD 3-Clause
- SLF4J: MIT License

Refer to `META-INF/LICENSE` and `META-INF/NOTICE` inside
`tika-app-2.6.0.jar` for the authoritative attribution list.

---

## bundles/org.camunda.bpm

- **Upstream project:** [Camunda Platform 7](https://camunda.com/products/camunda-platform-7/) 7.17.0 (Community Edition)
- **Primary license:** Apache License 2.0
- **Upstream LICENSE / NOTICE:**
  - <https://github.com/camunda/camunda-bpm-platform/blob/7.17.0/LICENSE>
  - <https://github.com/camunda/camunda-bpm-platform/blob/7.17.0/NOTICE>

| JAR | Project | License |
| --- | --- | --- |
| `camunda-bpmn-model-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-cmmn-model-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-commons-logging-1.10.0.jar` | [Camunda Commons](https://github.com/camunda/camunda-commons) | Apache License 2.0 |
| `camunda-commons-typed-values-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-commons-utils-1.10.0.jar` | Camunda Commons | Apache License 2.0 |
| `camunda-connect-connectors-all-1.5.2.jar` | [Camunda Connect](https://github.com/camunda/camunda-connect) | Apache License 2.0 |
| `camunda-connect-core-1.5.2.jar` | Camunda Connect | Apache License 2.0 |
| `camunda-dmn-model-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-dmn-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-feel-api-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-feel-juel-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-feel-scala-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-plugin-connect-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-engine-plugin-spin-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-identity-ldap-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `camunda-spin-core-1.14.0.jar` | [Camunda Spin](https://github.com/camunda/camunda-spin) | Apache License 2.0 |
| `camunda-spin-dataformat-all-1.14.0.jar` | Camunda Spin | Apache License 2.0 |
| `camunda-template-engines-freemarker-2.1.0.jar` | [Camunda Template Engines](https://github.com/camunda/camunda-template-engines) | Apache License 2.0 |
| `camunda-xml-model-7.17.0.jar` | Camunda Platform | Apache License 2.0 |
| `feel-engine-1.13.3-scala-shaded.jar` | [Camunda FEEL Engine](https://github.com/camunda/feel-scala) | Apache License 2.0 |
| `freemarker-2.3.31.jar` | [Apache FreeMarker](https://freemarker.apache.org/) | Apache License 2.0 |
| `graal-sdk-21.1.0.jar` | [GraalVM SDK](https://www.graalvm.org/) | Universal Permissive License (UPL) 1.0 |
| `groovy-all-2.4.13.jar` | Apache Groovy 2.4 | Apache License 2.0 |
| `icu4j-68.2.jar` | [ICU4J](https://icu.unicode.org/) | ICU License (Unicode-3.0 compatible) |
| `java-uuid-generator-3.2.0.jar` | [Java UUID Generator](https://github.com/cowtowncoder/java-uuid-generator) | Apache License 2.0 |
| `joda-time-2.1.jar` | [Joda-Time](https://www.joda.org/joda-time/) | Apache License 2.0 |
| `js-21.1.0.jar` | [GraalVM JavaScript](https://github.com/oracle/graaljs) | Universal Permissive License (UPL) 1.0 |
| `js-scriptengine-21.1.0.jar` | GraalVM JavaScript | Universal Permissive License (UPL) 1.0 |
| `mybatis-3.5.6.jar` | [MyBatis](https://mybatis.org/) | Apache License 2.0 |
| `regex-21.1.0.jar` | [GraalVM Regex (TRegex)](https://github.com/oracle/graal/tree/master/regex) | Universal Permissive License (UPL) 1.0 |
| `truffle-api-21.1.0.jar` | [GraalVM Truffle](https://github.com/oracle/graal/tree/master/truffle) | Universal Permissive License (UPL) 1.0 |

---

## bundles/org.mintjams.rt.cms.linux.x86_64

- **Upstream project (host bundle):** MintJams CMS native runtime support
  for Linux/x86_64 — first-party MintJams code (JNI bridge to V8).
- **Primary license:** Covered by the repository's top-level
  [`LICENSE`](../LICENSE).

This bundle ships compiled native shared objects under `native/linux/`.
`libnativeecma.so` is built from this project's own C++ source in
`native_src/` (see the bundle's
[`README.md`](org.mintjams.rt.cms.linux.x86_64/README.md) for build
instructions). The other `.so` files are produced from a Chromium / V8
component build (`is_component_build=true`) and are redistributed under
their respective upstream licenses.

| File | Upstream Project | License |
| --- | --- | --- |
| `libnativeecma.so` | MintJams JNI bridge for V8 (first-party) | Covered by the top-level [`LICENSE`](../LICENSE) |
| `libv8.so` | [Google V8 JavaScript Engine](https://v8.dev/) | BSD 3-Clause |
| `libv8_libplatform.so` | Google V8 (platform abstraction) | BSD 3-Clause |
| `libv8_libbase.so` | Google V8 (base library) | BSD 3-Clause |
| `libthird_party_abseil-cpp_absl.so` | [Abseil C++ Common Libraries](https://abseil.io/) (embedded by V8) | Apache License 2.0 |
| `libchrome_zlib.so` | [zlib](https://zlib.net/) (Chromium fork embedded by V8) | zlib License |

- V8 license: <https://chromium.googlesource.com/v8/v8/+/refs/heads/main/LICENSE>
- Abseil license: <https://github.com/abseil/abseil-cpp/blob/master/LICENSE>
- zlib license: <https://zlib.net/zlib_license.html>

Verbatim copies of these three upstream license texts are shipped
inside the bundle under
[`org.mintjams.rt.cms.linux.x86_64/native/linux/THIRD_PARTY_LICENSES/`](org.mintjams.rt.cms.linux.x86_64/native/linux/THIRD_PARTY_LICENSES/)
so the binary `.so` files travel together with their attribution
notices.

The exact V8 commit and the Abseil / zlib revisions pulled in by
`gclient sync` are recorded at build time per the build instructions in
the bundle's `README.md` (`V8_COMMIT.txt` and
`out/shared/args.used.txt`). Refer to the V8 source tree's top-level
`LICENSE` and `LICENSE.*` files for the authoritative attribution for
each transitively-embedded component.

---

## Notes

- License identifications above are based on the upstream project's
  declared license at the pinned version. Should an upstream relicense
  affect a future version bump, this document must be updated alongside
  `build.properties`.
- This file does not grant or modify any licenses; it is a record of the
  licenses under which each component is redistributed. The full text of
  each license, plus any required attribution notices, is shipped inside
  the respective JAR (typically under `META-INF/LICENSE` and
  `META-INF/NOTICE`).
- Bundles authored by MintJams Inc. (`org.mintjams.*`) and the
  pure-API bundle `slf4j-api-v2` are not covered here; they are governed
  by the repository's top-level `LICENSE`, or by the upstream SLF4J
  license respectively.
