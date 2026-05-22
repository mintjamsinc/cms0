# Third Party Licenses (Docker Image)

This document records license information for third-party software
redistributed inside the MintJams CMS container image. It enumerates the
JAR files laid down under `${FELIX_HOME}/bin/` and `${FELIX_HOME}/bundle/`
by the `COPY felix-dist/ ${FELIX_HOME}/` step in `docker/Dockerfile`.

The list below is grouped by upstream project. For each entry, the file
name shipped in the image is paired with the upstream project and the
license under which it is redistributed. Versions are pinned from the
Bundle-Version / Implementation-Version manifest headers of the JARs
themselves.

Four bundles in this image — `org.apache.camel_4.18.1.jar`,
`org.apache.groovy_4.0.25.jar`, `org.apache.tika_2.6.0.jar`, and
`org.camunda.bpm_7.17.0.jar` — are MintJams-repackaged OSGi wrappers
that embed the upstream JARs as nested `lib/*.jar` entries. The full
inventory of the JARs they embed is recorded in
[`../bundles/THIRD_PARTY_LICENSES.md`](../bundles/THIRD_PARTY_LICENSES.md)
and is not duplicated here.

Bundles authored by MintJams Inc. (`org.mintjams.*`) are governed by
the repository's top-level [`LICENSE`](../LICENSE) and are listed at the
end of this document for completeness.

Full license texts and required attribution notices are shipped inside
each JAR (typically under `META-INF/LICENSE` and `META-INF/NOTICE`) and
are also available from the upstream project links below.

---

## Apache Felix Framework and sub-projects

- **Upstream project:** [Apache Felix](https://felix.apache.org/)
- **Primary license:** Apache License 2.0
- **Upstream LICENSE / NOTICE:**
  - <https://github.com/apache/felix-dev/blob/master/LICENSE>
  - <https://github.com/apache/felix-dev/blob/master/NOTICE>

| JAR | Component | License |
| --- | --- | --- |
| `bin/felix.jar` | Apache Felix Framework 7.0.5 (`org.apache.felix.main`) | Apache License 2.0 |
| `bundle/org.apache.felix.bundlerepository-2.0.10.jar` | Apache Felix Bundle Repository (OBR) | Apache License 2.0 |
| `bundle/org.apache.felix.configadmin-1.9.26.jar` | Apache Felix Configuration Admin Service | Apache License 2.0 |
| `bundle/org.apache.felix.converter-1.0.12.jar` | Apache Felix Converter | Apache License 2.0 |
| `bundle/org.apache.felix.coordinator-1.0.2.jar` | Apache Felix Coordinator Service | Apache License 2.0 |
| `bundle/org.apache.felix.eventadmin-1.6.4.jar` | Apache Felix Event Admin Service | Apache License 2.0 |
| `bundle/org.apache.felix.http.base-5.1.10.jar` | Apache Felix HTTP Service - Base | Apache License 2.0 |
| `bundle/org.apache.felix.http.jetty-5.1.32.jar` | Apache Felix HTTP Service - Jetty (embeds [Eclipse Jetty](https://eclipse.dev/jetty/)) | Apache License 2.0 (Felix); embedded Jetty under Apache License 2.0 / Eclipse Public License 2.0 |
| `bundle/org.apache.felix.http.servlet-api-3.0.0.jar` | Apache Felix HTTP Servlet API | Apache License 2.0 |
| `bundle/org.apache.felix.http.sslfilter-proto-1.2.6.jar` | Apache Felix HTTP SSL Filter (X-Forwarded-Proto) | Apache License 2.0 |
| `bundle/org.apache.felix.http.webconsoleplugin-1.2.2.jar` | Apache Felix HTTP Web Console Plugin | Apache License 2.0 |
| `bundle/org.apache.felix.http.whiteboard-4.0.0.jar` | Apache Felix HTTP Whiteboard | Apache License 2.0 |
| `bundle/org.apache.felix.inventory-2.0.0.jar` | Apache Felix Inventory | Apache License 2.0 |
| `bundle/org.apache.felix.log-1.2.2.jar` | Apache Felix Log Service | Apache License 2.0 |
| `bundle/org.apache.felix.metatype-1.2.4.jar` | Apache Felix Metatype Service | Apache License 2.0 |
| `bundle/org.apache.felix.scr-2.2.0.jar` | Apache Felix Declarative Services (SCR) | Apache License 2.0 |
| `bundle/org.apache.felix.utils-1.11.0.jar` | Apache Felix Utils | Apache License 2.0 |
| `bundle/org.apache.felix.webconsole_4.8.2.jar` | Apache Felix Web Console | Apache License 2.0 |
| `bundle/org.apache.felix.webconsole.plugins.ds-2.3.0.jar` | Apache Felix Web Console - DS Plugin | Apache License 2.0 |
| `bundle/org.apache.felix.webconsole.plugins.event-1.2.0.jar` | Apache Felix Web Console - Event Plugin | Apache License 2.0 |
| `bundle/org.apache.felix.webconsole.plugins.memoryusage-1.1.0.jar` | Apache Felix Web Console - Memory Usage Plugin | Apache License 2.0 |

---

## OSGi Specifications

- **Upstream project:** [OSGi Working Group / Eclipse Foundation](https://www.osgi.org/)
- **Primary license:** Apache License 2.0
- **Upstream LICENSE:** <https://github.com/osgi/osgi/blob/main/LICENSE>

| JAR | Component | License |
| --- | --- | --- |
| `bundle/org.osgi.service.component_1.5.0.jar` | OSGi Declarative Services Specification API | Apache License 2.0 |
| `bundle/org.osgi.service.component.annotations_1.5.0.jar` | OSGi Declarative Services Annotations | Apache License 2.0 |
| `bundle/org.osgi.service.log_1.5.0.jar` | OSGi Log Service API | Apache License 2.0 |
| `bundle/org.osgi.service.log.stream_1.0.0.jar` | OSGi Log Stream API | Apache License 2.0 |
| `bundle/org.osgi.service.useradmin-1.1.1.jar` | OSGi User Admin Service API | Apache License 2.0 |
| `bundle/org.osgi.util.function_1.2.0.jar` | OSGi Util Function | Apache License 2.0 |
| `bundle/org.osgi.util.promise_1.2.0.jar` | OSGi Util Promise | Apache License 2.0 |
| `bundle/org.osgi.util.pushstream_1.0.2.jar` | OSGi Util Push Stream | Apache License 2.0 |

---

## JCR API

- **Upstream project:** [Content Repository for Java Technology API (JSR-283)](https://jcp.org/en/jsr/detail?id=283)
- **Primary license:** Day Specification License (BSD-style, royalty-free implementation grant)
- **Upstream LICENSE:** <https://search.maven.org/artifact/javax.jcr/jcr/2.0/jar> (LICENSE.txt in JAR)

| JAR | Component | License |
| --- | --- | --- |
| `bundle/jcr-2.0.jar` | JCR 2.0 API (`javax.jcr`) | Day Specification License |

---

## Database and Connection Pool

| JAR | Upstream Project | License |
| --- | --- | --- |
| `bundle/h2-2.3.232.jar` | [H2 Database Engine](https://h2database.com/) 2.3.232 | Dual: Mozilla Public License 2.0 / Eclipse Public License 1.0 (see <https://h2database.com/html/license.html>) |
| `bundle/HikariCP-7.0.1.jar` | [HikariCP](https://github.com/brettwooldridge/HikariCP) 7.0.1 | Apache License 2.0 |

---

## Cryptography

- **Upstream project:** [Bouncy Castle](https://www.bouncycastle.org/)
- **Primary license:** Bouncy Castle License (MIT-style; see <https://www.bouncycastle.org/licence.html>)

| JAR | Component | License |
| --- | --- | --- |
| `bundle/bcprov-jdk18on-1.83.jar` | Bouncy Castle Provider (JDK 1.8+) | Bouncy Castle License (MIT-style) |
| `bundle/bcpkix-jdk18on-1.83.jar` | Bouncy Castle PKIX / CMS / EAC / TSP / PKCS / OCSP / CMP / CRMF APIs | Bouncy Castle License (MIT-style) |
| `bundle/bcutil-jdk18on-1.83.jar` | Bouncy Castle ASN.1 / Crypto Utilities | Bouncy Castle License (MIT-style) |

---

## Apache Commons

- **Upstream project:** [Apache Commons](https://commons.apache.org/)
- **Primary license:** Apache License 2.0

| JAR | Upstream Project | License |
| --- | --- | --- |
| `bundle/commons-cli-1.9.0.jar` | [Apache Commons CLI](https://commons.apache.org/proper/commons-cli/) 1.9.0 | Apache License 2.0 |
| `bundle/commons-codec-1.18.0.jar` | [Apache Commons Codec](https://commons.apache.org/proper/commons-codec/) 1.18.0 | Apache License 2.0 |
| `bundle/commons-compress-1.27.1.jar` | [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) 1.27.1 | Apache License 2.0 |
| `bundle/commons-fileupload-1.5.jar` | [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) 1.5 | Apache License 2.0 |
| `bundle/commons-io-2.19.0.jar` | [Apache Commons IO](https://commons.apache.org/proper/commons-io/) 2.19.0 | Apache License 2.0 |
| `bundle/commons-jexl3-3.5.0.jar` | [Apache Commons JEXL](https://commons.apache.org/proper/commons-jexl/) 3.5.0 | Apache License 2.0 |
| `bundle/commons-lang3-3.17.0.jar` | [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/) 3.17.0 | Apache License 2.0 |
| `bundle/commons-pool2-2.12.1.jar` | [Apache Commons Pool](https://commons.apache.org/proper/commons-pool/) 2.12.1 | Apache License 2.0 |
| `bundle/commons-text-1.13.1.jar` | [Apache Commons Text](https://commons.apache.org/proper/commons-text/) 1.13.1 | Apache License 2.0 |

---

## FasterXML Jackson

- **Upstream project:** [FasterXML Jackson](https://github.com/FasterXML/jackson)
- **Primary license:** Apache License 2.0

| JAR | Component | License |
| --- | --- | --- |
| `bundle/jackson-annotations-2.13.3.jar` | Jackson Annotations 2.13.3 | Apache License 2.0 |
| `bundle/jackson-core-2.13.3.jar` | Jackson Core 2.13.3 | Apache License 2.0 |
| `bundle/jackson-databind-2.13.3.jar` | Jackson Databind 2.13.3 | Apache License 2.0 |

---

## SLF4J

- **Upstream project:** [SLF4J](https://www.slf4j.org/)
- **Primary license:** MIT License (<https://www.slf4j.org/license.html>)

| JAR | Component | License |
| --- | --- | --- |
| `bundle/slf4j-api-1.7.36.jar` | SLF4J API 1.7.36 | MIT License |
| `bundle/jcl-over-slf4j-1.7.36.jar` | JCL-over-SLF4J 1.7.36 (Apache Commons Logging bridge) | MIT License (the bridge is licensed under Apache License 2.0 by SLF4J upstream as well; refer to the JAR's LICENSE for the canonical text) |

The bundle `slf4j-api-v2_2.0.17.jar` is a MintJams-authored OSGi
repackaging of upstream SLF4J 2.0.17 API; its embedded JAR is
distributed under the SLF4J MIT License. See the MintJams-authored
section below.

---

## Other Third-Party Libraries

| JAR | Upstream Project | License |
| --- | --- | --- |
| `bundle/encoder-1.3.1.jar` | [OWASP Java Encoder](https://owasp.org/www-project-java-encoder/) 1.3.1 | BSD 3-Clause |
| `bundle/gson-2.9.0.jar` | [Google Gson](https://github.com/google/gson) 2.9.0 | Apache License 2.0 |
| `bundle/jansi-1.18.jar` | [Jansi](https://github.com/fusesource/jansi) 1.18 | Apache License 2.0 |
| `bundle/javax.activation-1.2.0.jar` | [JavaBeans Activation Framework](https://github.com/javaee/activation) 1.2.0 (GlassFish) | Eclipse Distribution License 1.0 (BSD 3-Clause) |
| `bundle/javax.mail-1.6.2.jar` | [JavaMail](https://javaee.github.io/javamail/) 1.6.2 | CDDL-1.1 / GPL-2.0-with-Classpath-Exception (dual) |
| `bundle/jline-3.13.2.jar` | [JLine 3](https://github.com/jline/jline3) 3.13.2 | BSD 3-Clause |
| `bundle/snakeyaml-engine-2.3.jar` | [SnakeYAML Engine](https://bitbucket.org/snakeyaml/snakeyaml-engine) 2.3 | Apache License 2.0 |

---

## MintJams-Repackaged Bundles

The following bundles are MintJams-authored OSGi wrappers that embed
upstream JARs as nested `lib/*.jar`. The licenses of the **embedded**
JARs are enumerated in
[`../bundles/THIRD_PARTY_LICENSES.md`](../bundles/THIRD_PARTY_LICENSES.md);
only the wrapper artifact is shown here.

| JAR | Upstream Project (Embedded) | Primary License |
| --- | --- | --- |
| `bundle/org.apache.camel_4.18.1.jar` | [Apache Camel](https://camel.apache.org/) 4.18.1 | Apache License 2.0 |
| `bundle/org.apache.groovy_4.0.25.jar` | [Apache Groovy](https://groovy.apache.org/) 4.0.25 | Apache License 2.0 |
| `bundle/org.apache.tika_2.6.0.jar` | [Apache Tika](https://tika.apache.org/) 2.6.0 | Apache License 2.0 |
| `bundle/org.camunda.bpm_7.17.0.jar` | [Camunda Platform 7](https://camunda.com/products/camunda-platform-7/) 7.17.0 Community Edition | Apache License 2.0 |

---

## MintJams-Authored Bundles

These bundles are first-party MintJams Inc. code (or MintJams-authored
OSGi packaging of upstream APIs) and are governed by the repository's
top-level [`LICENSE`](../LICENSE). They are not third-party software.

- `bundle/org.mintjams.cms_1.0.0.jar`
- `bundle/org.mintjams.idp_1.0.0.jar`
- `bundle/org.mintjams.jcr_1.0.0.jar`
- `bundle/org.mintjams.rt.cms_1.0.0.jar`
- `bundle/org.mintjams.rt.cms.linux.x86_64_1.0.0.jar` *(embeds third-party native libraries — see the next section)*
- `bundle/org.mintjams.rt.jcr_1.0.0.jar`
- `bundle/org.mintjams.rt.log_1.0.0.jar`
- `bundle/org.mintjams.rt.log.file_1.0.0.jar`
- `bundle/org.mintjams.rt.log.stdout_1.0.0.jar`
- `bundle/org.mintjams.rt.searchindex_1.0.0.jar`
- `bundle/org.mintjams.saml2_1.0.0.jar`
- `bundle/org.mintjams.searchindex_1.0.0.jar`
- `bundle/org.mintjams.tools_2.1.0.jar`
- `bundle/slf4j-api-v2_2.0.17.jar` *(MintJams-authored OSGi packaging of upstream [SLF4J](https://www.slf4j.org/) 2.0.17 API; the embedded API itself is MIT-licensed)*

### Native libraries embedded in `org.mintjams.rt.cms.linux.x86_64_1.0.0.jar`

The Linux/x86_64 native-support bundle ships the following shared
objects under its `native/linux/` directory. `libnativeecma.so` is
MintJams-authored JNI glue (governed by the top-level `LICENSE`); the
remaining `.so` files are produced from a Chromium / V8 component build
(`is_component_build=true`) and are redistributed under their
respective upstream licenses.

| File | Upstream Project | License |
| --- | --- | --- |
| `libnativeecma.so` | MintJams JNI bridge for V8 (first-party) | Covered by the top-level [`LICENSE`](../LICENSE) |
| `libv8.so` | [Google V8 JavaScript Engine](https://v8.dev/) | BSD 3-Clause (<https://chromium.googlesource.com/v8/v8/+/refs/heads/main/LICENSE>) |
| `libv8_libplatform.so` | Google V8 (platform abstraction) | BSD 3-Clause |
| `libv8_libbase.so` | Google V8 (base library) | BSD 3-Clause |
| `libthird_party_abseil-cpp_absl.so` | [Abseil C++ Common Libraries](https://abseil.io/) (embedded by V8) | Apache License 2.0 (<https://github.com/abseil/abseil-cpp/blob/master/LICENSE>) |
| `libchrome_zlib.so` | [zlib](https://zlib.net/) (Chromium fork embedded by V8) | zlib License (<https://zlib.net/zlib_license.html>) |

Verbatim copies of the V8, Abseil, and zlib upstream license texts are
shipped inside `bundle/org.mintjams.rt.cms.linux.x86_64_1.0.0.jar`
under `native/linux/THIRD_PARTY_LICENSES/` so the binary `.so` files
travel together with their attribution notices.

The exact V8 commit and Abseil / zlib revisions pulled in by `gclient
sync` are recorded at build time in `V8_COMMIT.txt` and
`out/shared/args.used.txt` per the build instructions in
[`bundles/org.mintjams.rt.cms.linux.x86_64/README.md`](../bundles/org.mintjams.rt.cms.linux.x86_64/README.md).
Refer to the V8 source tree's top-level `LICENSE` and
`LICENSE.v8`/`LICENSE.*` files for the authoritative attribution for
each transitively-embedded component.

---

## Notes

- License identifications above are based on each upstream project's
  declared license at the pinned version. Should an upstream relicense
  affect a future version bump, this document must be updated alongside
  the corresponding bundle revision.
- This file does not grant or modify any licenses; it is a record of the
  licenses under which each component is redistributed inside the
  container image.
- For the authoritative attribution text, refer to the `META-INF/LICENSE`
  and `META-INF/NOTICE` files inside each JAR.
- The base OS image (`eclipse-temurin:17-jre`) bundles the [Eclipse
  Temurin](https://adoptium.net/) JRE, distributed under the GNU General
  Public License v2 with the Classpath Exception, and a minimal
  Debian-based userland. Those layers are governed by their own license
  notices and are out of scope for this document.
