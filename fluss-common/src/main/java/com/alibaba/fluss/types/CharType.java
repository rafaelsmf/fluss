/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.types;

import com.alibaba.fluss.annotation.PublicStable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * data type of a fixed-length character string.
 *
 * <p>A conversion from and to {@code byte[]} assumes UTF-8 encoding.
 *
 * @since 0.1
 */
@PublicStable
public class CharType extends DataType {
    private static final long serialVersionUID = 1L;

    public static final int EMPTY_LITERAL_LENGTH = 0;

    public static final int MIN_LENGTH = 1;

    public static final int MAX_LENGTH = Integer.MAX_VALUE;

    public static final int DEFAULT_LENGTH = 1;

    private static final String FORMAT = "CHAR(%d)";

    private final int length;

    public CharType(boolean isNullable, int length) {
        super(isNullable, DataTypeRoot.CHAR);
        if (length < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    String.format(
                            "Character string length must be between %d and %d (both inclusive).",
                            MIN_LENGTH, MAX_LENGTH));
        }
        this.length = length;
    }

    public CharType(int length) {
        this(true, length);
    }

    public CharType() {
        this(DEFAULT_LENGTH);
    }

    public int getLength() {
        return length;
    }

    @Override
    public DataType copy(boolean isNullable) {
        return new CharType(isNullable, length);
    }

    @Override
    public String asSerializableString() {
        if (length == EMPTY_LITERAL_LENGTH) {
            throw new IllegalArgumentException(
                    "Zero-length character strings have no serializable string representation.");
        }
        return withNullability(FORMAT, length);
    }

    @Override
    public String asSummaryString() {
        return withNullability(FORMAT, length);
    }

    @Override
    public List<DataType> getChildren() {
        return Collections.emptyList();
    }

    @Override
    public <R> R accept(DataTypeVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        CharType charType = (CharType) o;
        return length == charType.length;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), length);
    }
}
