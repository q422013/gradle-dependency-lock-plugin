/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.ProjectSpec
import org.gradle.testfixtures.ProjectBuilder

class GenerateLockTaskSpec extends ProjectSpec {
    final String taskName = 'generateLock'

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'simple lock'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:2.+'
            testCompile 'test.example:baz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "1.1.0", "requested": "1.+" },
              "test.example:foo": { "locked": "2.0.1", "requested": "2.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'multiproject inter-project dependencies should be excluded'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            repositories { maven { url Fixture.repo } }
        }

        common.apply plugin: 'java'
        app.apply plugin: 'java'
        app.dependencies {
            compile app.project(':common')
            compile 'test.example:foo:2.+'
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:foo": { "locked": "2.0.1", "requested": "2.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'simple transitive lock'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:bar:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.+" },
              "test.example:foo": { "locked": "1.0.1", "transitive": true, "via": [ "test.example:bar" ] }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'check circular dependency does not loop infinitely'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'circular:a:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "circular:a": { "locked": "1.0.0", "requested": "1.+", "via": [ "circular:b" ] },
              "circular:b": { "locked": "1.0.0", "transitive": true, "via": [ "circular:a" ] }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'one level transitive test'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:bar:1.+'
            compile 'test.example:foobaz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.+" },
              "test.example:baz": { "locked": "1.0.0", "transitive": true, "via": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "1.0.1", "transitive": true, "via": [ "test.example:bar", "test.example:foobaz" ] },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'multi-level transitive test'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:transitive:1.0.0'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.0.0", "transitive": true, "via": [ "test.example:transitive" ] },
              "test.example:baz": { "locked": "1.0.0", "transitive": true, "via": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "1.0.1", "transitive": true, "via": [ "test.example:bar", "test.example:foobaz" ] },
              "test.example:foobaz": { "locked": "1.0.0", "transitive": true, "via": [ "test.example:transitive" ] },
              "test.example:transitive": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }
}