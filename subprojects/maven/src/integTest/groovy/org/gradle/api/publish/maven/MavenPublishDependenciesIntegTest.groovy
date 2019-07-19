/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.publish.maven

import org.gradle.integtests.fixtures.publish.maven.AbstractMavenPublishIntegTest
import spock.lang.Issue
import spock.lang.Unroll

class MavenPublishDependenciesIntegTest extends AbstractMavenPublishIntegTest {
    def repoModule = javaLibrary(mavenRepo.module('group', 'root', '1.0'))

    @Issue('GRADLE-1574')
    def "publishes wildcard exclusions for a non-transitive dependency"() {
        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            apply plugin: 'maven-publish'
            apply plugin: 'java-library'

            group = 'group'
            version = '1.0'

            dependencies {
                api('org.test:non-transitive:1.0') { transitive = false }
                api 'org.test:artifact-only:1.0@jar'
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """

        when:
        succeeds 'publish'

        then: "wildcard exclusions are applied to the dependencies"
        repoModule.assertApiDependencies('org.test:non-transitive:1.0', 'org.test:artifact-only:1.0')

        def pom = repoModule.parsedPom
        ['non-transitive', 'artifact-only'].each {
            def exclusions = pom.scopes.compile.dependencies["org.test:${it}:1.0"].exclusions
            assert exclusions.size() == 1
            assert exclusions[0].groupId=='*'
            assert exclusions[0].artifactId=='*'
        }
    }

    @Issue("GRADLE-3233")
    @Unroll
    def "publishes POM dependency with #versionType version for Gradle dependency with null version"() {
        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            apply plugin: 'maven-publish'
            apply plugin: 'java-library'

            group = 'group'
            version = '1.0'

            dependencies {
                api $dependencyNotation
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """

        when:
        succeeds 'publish'

        then:
        repoModule.assertPublished()
        repoModule.assertApiDependencies("group:projectA:")
        def dependency = repoModule.parsedPom.scopes.compile.dependencies.get("group:projectA:")
        dependency.groupId == "group"
        dependency.artifactId == "projectA"
        dependency.version == ""

        where:
        versionType | dependencyNotation
        "empty"     | "'group:projectA'"
        "null"      | "group:'group', name:'projectA', version:null"
    }

    void "defaultDependencies are included in published pom file"() {
        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            apply plugin: "java-library"
            apply plugin: "maven-publish"

            group = 'group'
            version = '1.0'

            configurations.api.defaultDependencies { deps ->
                deps.add project.dependencies.create("org:default-dependency:1.0")
            }
            configurations.implementation.defaultDependencies { deps ->
                deps.add project.dependencies.create("org:default-dependency:1.0")
            }
            dependencies {
                implementation "org:explicit-dependency:1.0"
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """

        when:
        succeeds "publish"

        then:
        repoModule.assertPublished()
        repoModule.assertApiDependencies('org:default-dependency:1.0')
        repoModule.assertRuntimeDependencies('org:explicit-dependency:1.0')
    }

    void "dependency mutations are reflected in published pom file"() {
        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            apply plugin: "java-library"
            apply plugin: "maven-publish"

            group = 'group'
            version = '1.0'

            dependencies {
                api "org.test:dep1:1.0"
            }
            configurations.api.withDependencies { deps ->
                deps.each { dep ->
                    dep.version { require 'X' }
                }
                deps.add project.dependencies.create("org.test:dep2:1.0")
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """

        when:
        succeeds "publish"

        then:
        repoModule.assertPublished()
        repoModule.assertApiDependencies('org.test:dep1:X', 'org.test:dep2:1.0')
    }

    def "publishes both dependencies when one has a classifier"() {
        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            apply plugin: "java-library"
            apply plugin: "maven-publish"

            group = 'group'
            version = '1.0'

            dependencies {
                implementation "org:foo:1.0"
                implementation "org:foo:1.0:classy"
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """

        when:
        succeeds "publish"

        then:
        repoModule.assertPublished()
        repoModule.assertApiDependencies()
        repoModule.parsedPom.scope("runtime") {
            def deps = dependencies.values()
            assert deps.size() == 2
            assert deps[0].classifier == null
            assert deps[1].classifier == "classy"
        }
        repoModule.parsedModuleMetadata.variant("runtimeElements") {
            dependency("org:foo:1.0") {
                // first dependency
                exists()
                noAttributes()
                // second dependency
                next()
                exists()
                hasAttribute("org.gradle.compat.artifactselector", "foo:jar:jar:classy")
                isLast()
            }
        }
    }

}
