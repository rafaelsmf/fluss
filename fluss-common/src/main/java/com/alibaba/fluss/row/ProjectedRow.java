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

package com.alibaba.fluss.row;

import com.alibaba.fluss.annotation.PublicEvolving;

import java.util.Arrays;

/**
 * An implementation of {@link InternalRow} which provides a projected view of the underlying {@link
 * InternalRow}.
 *
 * <p>Projection includes both reducing the accessible fields and reordering them.
 *
 * <p>Note: This class supports only top-level projections, not nested projections.
 */
@PublicEvolving
public class ProjectedRow implements InternalRow {

    private final int[] indexMapping;

    private InternalRow row;

    private ProjectedRow(int[] indexMapping) {
        this.indexMapping = indexMapping;
    }

    /**
     * Replaces the underlying {@link InternalRow} backing this {@link ProjectedRow}.
     *
     * <p>This method replaces the row data in place and does not return a new object. This is done
     * for performance reasons.
     */
    public ProjectedRow replaceRow(InternalRow row) {
        this.row = row;
        return this;
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public int getFieldCount() {
        return indexMapping.length;
    }

    @Override
    public boolean isNullAt(int pos) {
        return row.isNullAt(indexMapping[pos]);
    }

    @Override
    public boolean getBoolean(int pos) {
        return row.getBoolean(indexMapping[pos]);
    }

    @Override
    public byte getByte(int pos) {
        return row.getByte(indexMapping[pos]);
    }

    @Override
    public short getShort(int pos) {
        return row.getShort(indexMapping[pos]);
    }

    @Override
    public int getInt(int pos) {
        return row.getInt(indexMapping[pos]);
    }

    @Override
    public long getLong(int pos) {
        return row.getLong(indexMapping[pos]);
    }

    @Override
    public float getFloat(int pos) {
        return row.getFloat(indexMapping[pos]);
    }

    @Override
    public double getDouble(int pos) {
        return row.getDouble(indexMapping[pos]);
    }

    @Override
    public BinaryString getChar(int pos, int length) {
        return row.getChar(indexMapping[pos], length);
    }

    @Override
    public BinaryString getString(int pos) {
        return row.getString(indexMapping[pos]);
    }

    @Override
    public Decimal getDecimal(int pos, int precision, int scale) {
        return row.getDecimal(indexMapping[pos], precision, scale);
    }

    @Override
    public TimestampNtz getTimestampNtz(int pos, int precision) {
        return row.getTimestampNtz(indexMapping[pos], precision);
    }

    @Override
    public TimestampLtz getTimestampLtz(int pos, int precision) {
        return row.getTimestampLtz(indexMapping[pos], precision);
    }

    @Override
    public byte[] getBinary(int pos, int length) {
        return row.getBinary(indexMapping[pos], length);
    }

    @Override
    public byte[] getBytes(int pos) {
        return row.getBytes(indexMapping[pos]);
    }

    @Override
    public boolean equals(Object o) {
        throw new UnsupportedOperationException("Projected row data cannot be compared");
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException("Projected row data cannot be hashed");
    }

    @Override
    public String toString() {
        return "{" + "indexMapping=" + Arrays.toString(indexMapping) + ", row=" + row + '}';
    }

    /**
     * Create an empty {@link ProjectedRow} starting from a {@code projection} array.
     *
     * <p>The array represents the mapping of the fields of the original {@link InternalRow}. For
     * example, {@code [0, 2, 1]} specifies to include in the following order the 1st field, the 3rd
     * field and the 2nd field of the row.
     *
     * @see ProjectedRow
     */
    public static ProjectedRow from(int[] projection) {
        return new ProjectedRow(projection);
    }
}
