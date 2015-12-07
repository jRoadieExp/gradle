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

package org.gradle.language.java

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

import static org.gradle.language.java.JavaIntegrationTesting.applyJavaPlugin

class JavaJvmAssemblyIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        applyJavaPlugin buildFile
        buildFile << '''
            model {
                components {
                    main(JvmLibrarySpec)
                }
                tasks.processMainJarMainResources {
                    // remove lines that start with #
                    filter {
                        it.startsWith('#') ? null : it
                    }
                }
            }
        '''
        file('src/main/java/myorg/Main.java') << 'package myorg; class Main {}'
        file('src/main/resources/myorg/answer.txt') << '# yadda\n42\n# yadda'
    }

    def "can create task that depends on assembly and jar is *not* built"() {
        given:
        buildFile << '''
            model {
                tasks { ts ->
                    $.components.main.binaries { binaries ->
                        def binary = binaries.values().first()
                        ts.create('taskThatDependsOnAssembly') {
                            dependsOn binary.assembly
                            doFirst {
                                def hasOrNot = binary.jarFile.exists() ? 'has' : 'has not'
                                println "The jar $hasOrNot been built."

                                def relativize = { root, file ->
                                    root.toURI().relativize(file.toURI()).toString()
                                }

                                def classes = []
                                binary.assembly.classDirectories.each { dir ->
                                    dir.eachFileRecurse(groovy.io.FileType.FILES) {
                                        classes << relativize(dir, it)
                                    }
                                }
                                println "Classes were generated: $classes"

                                def resources = []
                                binary.assembly.resourceDirectories.each { dir ->
                                    dir.eachFileRecurse(groovy.io.FileType.FILES) {
                                        resources << "${relativize(dir, it)} => ${it.text.trim()}"
                                    }
                                }
                                println "Resources were processed: $resources"
                            }
                        }
                    }
                }
            }
        '''

        expect:
        succeeds 'taskThatDependsOnAssembly'

        and:
        outputContains 'The jar has not been built.'
        outputContains 'Classes were generated: [myorg/Main.class]'
        outputContains 'Resources were processed: [myorg/answer.txt => 42]'
    }
}