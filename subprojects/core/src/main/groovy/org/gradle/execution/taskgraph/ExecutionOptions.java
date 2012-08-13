/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.execution.taskgraph;

class ExecutionOptions {
    private final int parallelExecutors;

    public ExecutionOptions(int parallelExecutors) {
        this.parallelExecutors = parallelExecutors;
    }

    public boolean executeProjectsInParallel() {
        return parallelExecutors != 0;
    }

    public int numberOfParallelExecutors() {
        if (parallelExecutors == -1) {
            return Math.min(1, Runtime.getRuntime().availableProcessors());
        }
        return Math.min(1, parallelExecutors);
    }
}