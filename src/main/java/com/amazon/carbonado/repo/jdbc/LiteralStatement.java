/*
 * Copyright 2007-2012 Amazon Technologies, Inc. or its affiliates.
 * Amazon, Amazon.com and Carbonado are trademarks or registered trademarks
 * of Amazon Technologies, Inc. or its affiliates.  All rights reserved.
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

package com.amazon.carbonado.repo.jdbc;

import com.amazon.carbonado.Storable;

import com.amazon.carbonado.filter.FilterValues;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class LiteralStatement<S extends Storable> extends SQLStatement<S> {
    private final String mStr;

    LiteralStatement(String str) {
        mStr = str;
    }

    @Override
    public int maxLength() {
        return mStr.length();
    }

    @Override
    public String buildStatement(int initialCapacity, FilterValues<S> filterValues) {
        return mStr;
    }

    @Override
    public void appendTo(StringBuilder b, FilterValues<S> filterValues) {
        b.append(mStr);
    }

    /**
     * Returns the literal value.
     */
    @Override
    public String toString() {
        return mStr;
    }
}
