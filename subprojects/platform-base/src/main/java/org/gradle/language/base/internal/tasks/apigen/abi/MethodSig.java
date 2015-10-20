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
package org.gradle.language.base.internal.tasks.apigen.abi;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Set;

public class MethodSig implements Comparable<MethodSig> {
    private final int access;
    private final String name;
    private final String desc;
    private final String signature;
    private final Set<String> exceptions;

    public MethodSig(int access, String name, String desc, String signature, String[] exceptions) {
        this.access = access;
        this.name = name;
        this.desc = desc;
        this.signature = signature;
        this.exceptions = exceptions == null ? Collections.<String>emptySet() : Sets.newTreeSet(ImmutableList.copyOf(exceptions));
    }

    public int getAccess() {
        return access;
    }

    public String getDesc() {
        return desc;
    }

    public Set<String> getExceptions() {
        return exceptions;
    }

    public String getName() {
        return name;
    }

    public String getSignature() {
        return signature;
    }

    @Override
    public int compareTo(MethodSig o) {
        return ComparisonChain.start()
            .compare(access, o.access)
            .compare(name, o.name)
            .compare(desc == null ? "" : desc, o.desc == null ? "" : o.desc)
            .compare(signature == null ? "" : signature, o.signature == null ? "" : o.signature)
            .compare(exceptions, o.exceptions, Ordering.<String>natural().lexicographical())
            .result();
    }
}