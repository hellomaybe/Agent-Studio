# Third-party notices

These notices cover upstream software used to build or run Agent Studio. The project's original community code is licensed under the [Apache License 2.0](LICENSE). Each upstream component retains its own terms.

## Source distribution

Keep the Vben copyright and full MIT notice with source derived from the original admin project. The standalone frontend uses Vue, Element Plus and Vue Flow directly. The retained Vben notice records the source project's provenance. [Vben MIT notice](licenses/Vben-MIT.txt) · [Upstream license](https://github.com/vbenjs/vue-vben-admin/blob/main/LICENSE)

The source distribution contains application code, dependency declarations and a frontend lockfile. The package managers obtain third-party code at build time. Preserve existing file-level notices when copying or adapting any upstream source.

## Browser application

| Direct package | Locked version | License |
| --- | --- | --- |
| Vue | 3.5.38 | MIT |
| Element Plus | 2.14.2 | MIT |
| Element Plus icons for Vue | 2.3.2 | MIT |
| Vue Flow Core | 1.48.2 | MIT |

The frontend notice file contains upstream license text and copyright notices copied from the installed packages. It covers all four direct packages, the 13 package/version pairs identified by Vite's production bundle license collector, and additional packages in Vue Flow's dependency tree. The combined inventory has 27 package/version pairs. Shared texts appear once with all applicable packages listed. [Frontend runtime notices](licenses/frontend-runtime.txt) · [Locked dependencies](frontend/package-lock.json)

Vue Flow's distributed ES module incorporates dependency code, including D3 functions. A bundler examining the published module sees a single module. Additional notices therefore include the D3 family, VueUse 10 and vue-demi. Their version labels identify the local resolution. The upstream prebuilt module does not identify every embedded dependency's exact version. The bundle also uses the MIT-licensed Popper fork published as `@sxzz/popperjs-es` and installed under the alias `@popperjs/core`. The included Lodash and normalize-wheel texts retain their upstream attributions.

The Vite build copies both frontend notices into `dist/licenses/`. Distribute that directory with the static application. Update the notice inventory after changing the lockfile or bundled dependencies.

## Java application

The default Spring Boot JAR contained **82** nested runtime JARs when reviewed. The inventory records their versions, POM license declarations, legal files located inside each nested JAR and upstream POM links. Names in the inventory reflect the upstream metadata. Where a POM inherits its license, the linked POM identifies its parent. The notice collection contains 19 distinct NOTICE texts found in these JARs, including notices from OkHttp's public-suffix resource, Tomcat and Spring Framework. [Runtime inventory](licenses/backend-runtime.tsv) · [Embedded upstream notices](licenses/backend-embedded-NOTICES.txt)

| Component | Source for the reviewed version |
| --- | --- |
| Spring Boot 4.0.3 and Spring AI 2.0.3 | Apache 2.0. The OpenAI integration uses the Spring AI model and its official SDK dependency. [Spring AI license text](licenses/Apache-2.0.txt) · [Spring AI OpenAI POM](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/2.0.3/spring-ai-openai-2.0.3.pom) |
| OpenAI Java core 4.101.0 | Apache 2.0. This license covers the SDK code. The deployment supplies credentials and uses its selected model provider under that provider's service terms. [SDK license](https://github.com/openai/openai-java/blob/v4.101.0/LICENSE) |
| H2 2.4.240 | The H2 Group offers a choice of MPL 2.0 or EPL 1.0. For binary redistribution, document the chosen license and provide the corresponding source access it requires. [Both license texts from the release tag](licenses/H2-2.4.240-LICENSE.txt) · [Matching upstream source](https://github.com/h2database/h2database/tree/version-2.4.240) |
| Flyway core 11.14.1 | The copy in the JAR contains Red Gate's Apache 2.0 notice. The Maven parent declares Apache 2.0. [Embedded notice](licenses/Flyway-11.14.1-license.txt) · [Maven parent](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-parent/11.14.1/flyway-parent-11.14.1.pom) |

For a runnable JAR or container, retain the original nested JAR license and notice files and distribute accessible notices with the binary. Reconcile any files absent from a JAR with the upstream release before distributing it. A POM license name alone does not reproduce a copyright notice or satisfy a source-availability obligation. Examples needing attention include the Logback dual-license options, Jakarta Annotations, ANTLR/StringTemplate and jtokkit. The inventory covers the reviewed default JAR. A container also requires an inventory for its base image and Java runtime.

## Optional MySQL profile

The `mysql` Maven profile adds Flyway's MySQL module and MySQL Connector/J. The reviewed default JAR uses H2 and excludes both MySQL libraries. [Backend profiles](backend/pom.xml)

* **Flyway MySQL 11.14.1** inherits the Flyway parent POM license declaration. [Module POM](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-mysql/11.14.1/flyway-mysql-11.14.1.pom)
* **MySQL Connector/J 9.6.0 Community** declares GPL version 2 with the Universal FOSS Exception version 1.0. Its distribution includes Oracle's version-specific licensing manual and third-party notices in the JAR's root `LICENSE`. The connector also declares Protobuf as a dependency. Review the exception against the chosen project license and distribution, retain the manual and review the actual profile's transitive dependencies and source obligations before shipping a MySQL-enabled binary. [Connector POM](https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.6.0/mysql-connector-j-9.6.0.pom) · [Version-specific JAR containing the manual](https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.6.0/mysql-connector-j-9.6.0.jar) · [Oracle Universal FOSS Exception](https://oss.oracle.com/licenses/universal-foss-exception/)
