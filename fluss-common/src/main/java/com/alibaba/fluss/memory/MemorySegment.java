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

package com.alibaba.fluss.memory;

import com.alibaba.fluss.annotation.Internal;

import javax.annotation.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

import static com.alibaba.fluss.memory.MemoryUtils.getByteBufferAddress;
import static com.alibaba.fluss.utils.Preconditions.checkNotNull;

/* This file is based on source code of Apache Flink Project (https://flink.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * This class represents a piece of memory managed by Fluss.
 *
 * <p>The memory can be on-heap, off-heap direct or off-heap unsafe. This is transparently handled
 * by this class.
 *
 * <p>This class fulfills conceptually a similar purpose as Java's {@link ByteBuffer}. We add this
 * specialized class for various reasons:
 *
 * <ul>
 *   <li>It offers additional binary compare, swap, and copy methods.
 *   <li>It uses collapsed checks for range check and memory segment disposal.
 *   <li>It offers absolute positioning methods for bulk put/get methods, to guarantee thread safe
 *       use.
 *   <li>It offers explicit big-endian / little-endian access methods, rather than tracking
 *       internally a byte order.
 *   <li>It transparently and efficiently moves data between on-heap and off-heap variants.
 * </ul>
 *
 * <p><i>Comments on the implementation</i>: We make heavy use of operations that are supported by
 * native instructions, to achieve a high efficiency. Multi byte types (int, long, float, double,
 * ...) are read and written with "unsafe" native commands.
 *
 * <p><i>Note on efficiency</i>: For best efficiency, we do not separate implementations of
 * different memory types with inheritance, to avoid the overhead from looking for concrete
 * implementations on invocations of abstract methods.
 */
@Internal
public final class MemorySegment {

    /** The unsafe handle for transparent memory copied (heap / off-heap). */
    private static final sun.misc.Unsafe UNSAFE = MemoryUtils.UNSAFE;

    /** The beginning of the byte array contents, relative to the byte array object. */
    private static final long BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);

    /**
     * Constant that flags the byte order. Because this is a boolean constant, the JIT compiler can
     * use this well to aggressively eliminate the non-applicable code paths.
     */
    public static final boolean LITTLE_ENDIAN =
            (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);

    // ------------------------------------------------------------------------

    /**
     * The heap byte array object relative to which we access the memory.
     *
     * <p>Is non-<tt>null</tt> if the memory is on the heap, and is <tt>null</tt>, if the memory is
     * off the heap. If we have this buffer, we must never void this reference, or the memory
     * segment will point to undefined addresses outside the heap and may in out-of-order execution
     * cases cause segmentation faults.
     */
    @Nullable private final byte[] heapMemory;

    /**
     * The direct byte buffer that wraps the off-heap memory. This memory segment holds a reference
     * to that buffer, so as long as this memory segment lives, the memory will not be released.
     */
    @Nullable private final ByteBuffer offHeapBuffer;

    /**
     * The address to the data, relative to the heap memory byte array. If the heap memory byte
     * array is <tt>null</tt>, this becomes an absolute memory address outside the heap.
     */
    private long address;

    /**
     * The address one byte after the last addressable byte, i.e. <tt>address + size</tt> while the
     * segment is not disposed.
     */
    private final long addressLimit;

    /** The size in bytes of the memory segment. */
    private final int size;

    /**
     * Creates a new memory segment that represents the memory of the byte array.
     *
     * <p>Since the byte array is backed by on-heap memory, this memory segment holds its data on
     * heap. The buffer must be at least of size 8 bytes.
     */
    private MemorySegment(
            @Nullable byte[] heapMemory,
            @Nullable ByteBuffer offHeapBuffer,
            long address,
            int size) {
        this.heapMemory = heapMemory;
        this.offHeapBuffer = offHeapBuffer;
        this.address = address;
        this.size = size;
        this.addressLimit = this.address + this.size;
    }

    public static MemorySegment wrap(byte[] buffer) {
        return new MemorySegment(buffer, null, BYTE_ARRAY_BASE_OFFSET, buffer.length);
    }

    public static MemorySegment wrapOffHeapMemory(ByteBuffer buffer) {
        return new MemorySegment(null, buffer, getByteBufferAddress(buffer), buffer.capacity());
    }

    public static MemorySegment allocateHeapMemory(int size) {
        return wrap(new byte[size]);
    }

    public static MemorySegment allocateOffHeapMemory(int size) {
        return wrapOffHeapMemory(ByteBuffer.allocateDirect(size));
    }

    // ------------------------------------------------------------------------
    // Memory Segment Operations
    // ------------------------------------------------------------------------

    /**
     * Gets the size of the memory segment, in bytes.
     *
     * @return The size of the memory segment.
     */
    public int size() {
        return size;
    }

    /**
     * Checks whether the memory segment was freed.
     *
     * @return <tt>true</tt>, if the memory segment has been freed, <tt>false</tt> otherwise.
     */
    public boolean isFreed() {
        return address > addressLimit;
    }

    /**
     * Frees this memory segment.
     *
     * <p>After this operation has been called, no further operations are possible on the memory
     * segment and will fail. The actual memory (heap or off-heap) will only be released after this
     * memory segment object has become garbage collected.
     */
    public void free() {
        if (isFreed()) {
            throw new IllegalStateException("MemorySegment can be freed only once!");
        }
        // this ensures we can place no more data and trigger
        // the checks for the freed segment
        address = addressLimit + 1;
    }

    /**
     * Checks whether this memory segment is backed by off-heap memory.
     *
     * @return <tt>true</tt>, if the memory segment is backed by off-heap memory, <tt>false</tt> if
     *     it is backed by heap memory.
     */
    public boolean isOffHeap() {
        return heapMemory == null;
    }

    /**
     * Returns the byte array of on-heap memory segments.
     *
     * @return underlying byte array
     * @throws IllegalStateException if the memory segment does not represent on-heap memory
     */
    public byte[] getArray() {
        if (heapMemory != null) {
            return heapMemory;
        } else {
            throw new IllegalStateException("Memory segment does not represent heap memory");
        }
    }

    /**
     * Returns the off-heap buffer of memory segments.
     *
     * @return underlying off-heap buffer
     * @throws IllegalStateException if the memory segment does not represent off-heap buffer
     */
    public ByteBuffer getOffHeapBuffer() {
        if (offHeapBuffer != null) {
            return offHeapBuffer;
        } else {
            throw new IllegalStateException("Memory segment does not represent off-heap buffer");
        }
    }

    /**
     * Returns the memory address of off-heap memory segments.
     *
     * @return absolute memory address outside the heap
     * @throws IllegalStateException if the memory segment does not represent off-heap memory
     */
    public long getAddress() {
        if (heapMemory == null) {
            return address;
        } else {
            throw new IllegalStateException("Memory segment does not represent off heap memory");
        }
    }

    /**
     * Wraps the chunk of the underlying memory located between <tt>offset</tt> and <tt>offset +
     * length</tt> in a NIO ByteBuffer. The ByteBuffer has the full segment as capacity and the
     * offset and length parameters set the buffers position and limit.
     *
     * @param offset The offset in the memory segment.
     * @param length The number of bytes to be wrapped as a buffer.
     * @return A <tt>ByteBuffer</tt> backed by the specified portion of the memory segment.
     * @throws IndexOutOfBoundsException Thrown, if offset is negative or larger than the memory
     *     segment size, or if the offset plus the length is larger than the segment size.
     */
    public ByteBuffer wrap(int offset, int length) {
        return wrapInternal(offset, length);
    }

    private ByteBuffer wrapInternal(int offset, int length) {
        if (address <= addressLimit) {
            if (heapMemory != null) {
                return ByteBuffer.wrap(heapMemory, offset, length);
            } else {
                try {
                    ByteBuffer wrapper = checkNotNull(offHeapBuffer).duplicate();
                    wrapper.limit(offset + length);
                    wrapper.position(offset);
                    return wrapper;
                } catch (IllegalArgumentException e) {
                    throw new IndexOutOfBoundsException();
                }
            }
        } else {
            throw new IllegalStateException("segment has been freed");
        }
    }

    // ------------------------------------------------------------------------
    //                    Random Access get() and put() methods
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Notes on the implementation: We try to collapse as many checks as
    // possible. We need to obey the following rules to make this safe
    // against segfaults:
    //
    //  - Grab mutable fields onto the stack before checking and using. This
    //    guards us against concurrent modifications which invalidate the
    //    pointers
    //  - Use subtractions for range checks, as they are tolerant
    // ------------------------------------------------------------------------

    /**
     * Reads the byte at the given position.
     *
     * @param index The position from which the byte will be read
     * @return The byte at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger or equal to the
     *     size of the memory segment.
     */
    public byte get(int index) {
        final long pos = address + index;
        if (index >= 0 && pos < addressLimit) {
            return UNSAFE.getByte(heapMemory, pos);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Writes the given byte into this buffer at the given position.
     *
     * @param index The index at which the byte will be written.
     * @param b The byte value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger or equal to the
     *     size of the memory segment.
     */
    public void put(int index, byte b) {
        final long pos = address + index;
        if (index >= 0 && pos < addressLimit) {
            UNSAFE.putByte(heapMemory, pos, b);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException(
                    "capacity: " + size + ", index: " + index + ", put length: 1");
        }
    }

    /**
     * Bulk get method. Copies dst.length memory from the specified position to the destination
     * memory.
     *
     * @param index The position at which the first byte will be read.
     * @param dst The memory into which the memory will be copied.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or too large that the
     *     data between the index and the memory segment end is not enough to fill the destination
     *     array.
     */
    public void get(int index, byte[] dst) {
        get(index, dst, 0, dst.length);
    }

    /**
     * Bulk put method. Copies src.length memory from the source memory into the memory segment
     * beginning at the specified position.
     *
     * @param index The index in the memory segment array, where the data is put.
     * @param src The source array to copy the data from.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or too large such that
     *     the array size exceed the amount of memory between the index and the memory segment's
     *     end.
     */
    public void put(int index, byte[] src) {
        put(index, src, 0, src.length);
    }

    /**
     * Bulk get method. Copies length memory from the specified position to the destination memory,
     * beginning at the given offset.
     *
     * @param index The position at which the first byte will be read.
     * @param dst The memory into which the memory will be copied.
     * @param offset The copying offset in the destination memory.
     * @param length The number of bytes to be copied.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or too large that the
     *     requested number of bytes exceed the amount of memory between the index and the memory
     *     segment's end.
     */
    public void get(int index, byte[] dst, int offset, int length) {
        // check the byte array offset and length and the status
        if ((offset | length | (offset + length) | (dst.length - (offset + length))) < 0) {
            throw new IndexOutOfBoundsException();
        }

        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - length) {
            final long arrayAddress = BYTE_ARRAY_BASE_OFFSET + offset;
            UNSAFE.copyMemory(heapMemory, pos, dst, arrayAddress, length);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "pos: %d, length: %d, index: %d, offset: %d",
                            pos, length, index, offset));
        }
    }

    /**
     * Bulk put method. Copies length memory starting at position offset from the source memory into
     * the memory segment starting at the specified index.
     *
     * @param index The position in the memory segment array, where the data is put.
     * @param src The source array to copy the data from.
     * @param offset The offset in the source array where the copying is started.
     * @param length The number of bytes to copy.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or too large such that
     *     the array portion to copy exceed the amount of memory between the index and the memory
     *     segment's end.
     */
    public void put(int index, byte[] src, int offset, int length) {
        // check the byte array offset and length
        if ((offset | length | (offset + length) | (src.length - (offset + length))) < 0) {
            throw new IndexOutOfBoundsException();
        }

        final long pos = address + index;

        if (index >= 0 && pos <= addressLimit - length) {
            final long arrayAddress = BYTE_ARRAY_BASE_OFFSET + offset;
            UNSAFE.copyMemory(src, arrayAddress, heapMemory, pos, length);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException(
                    "capacity: " + size + ", index: " + index + ", put length: " + length);
        }
    }

    /**
     * Reads one byte at the given position and returns its boolean representation.
     *
     * @param index The position from which the memory will be read.
     * @return The boolean value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 1.
     */
    public boolean getBoolean(int index) {
        return get(index) != 0;
    }

    /**
     * Writes one byte containing the byte value into this buffer at the given position.
     *
     * @param index The position at which the memory will be written.
     * @param value The char value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 1.
     */
    public void putBoolean(int index, boolean value) {
        put(index, (byte) (value ? 1 : 0));
    }

    /**
     * Reads a character value (16 bit, 2 bytes) from the given position, in little-endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #getCharNativeEndian(int)}. For most cases (such as transient storage in
     * memory or serialization for I/O and network), it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read, and {@link
     * #getCharNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The character value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public char getChar(int index) {
        if (LITTLE_ENDIAN) {
            return getCharNativeEndian(index);
        } else {
            return Character.reverseBytes(getCharNativeEndian(index));
        }
    }

    /**
     * Reads a char value from the given position, in the system's native byte order.
     *
     * @param index The position from which the memory will be read.
     * @return The char value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    @SuppressWarnings("restriction")
    public char getCharNativeEndian(int index) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 2) {
            return UNSAFE.getChar(heapMemory, pos);
        } else if (address > addressLimit) {
            throw new IllegalStateException("This segment has been freed.");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reads a character value (16 bit, 2 bytes) from the given position, in big-endian byte order.
     * This method's speed depends on the system's native byte order, and it is possibly slower than
     * {@link #getCharNativeEndian(int)}. For most cases (such as transient storage in memory or
     * serialization for I/O and network), it suffices to know that the byte order in which the
     * value is written is the same as the one in which it is read, and {@link
     * #getCharNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The character value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public char getCharBigEndian(int index) {
        if (LITTLE_ENDIAN) {
            return Character.reverseBytes(getCharNativeEndian(index));
        } else {
            return getCharNativeEndian(index);
        }
    }

    /**
     * Writes the given character (16 bit, 2 bytes) to the given position in little-endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #putCharNativeEndian(int, char)}. For most cases (such as transient
     * storage in memory or serialization for I/O and network), it suffices to know that the byte
     * order in which the value is written is the same as the one in which it is read, and {@link
     * #putCharNativeEndian(int, char)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The char value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public void putChar(int index, char value) {
        if (LITTLE_ENDIAN) {
            putCharNativeEndian(index, value);
        } else {
            putCharNativeEndian(index, Character.reverseBytes(value));
        }
    }

    /**
     * Writes a char value to the given position, in the system's native byte order.
     *
     * @param index The position at which the memory will be written.
     * @param value The char value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    @SuppressWarnings("restriction")
    public void putCharNativeEndian(int index, char value) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 2) {
            UNSAFE.putChar(heapMemory, pos, value);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException(
                    "capacity: " + size + ", index: " + index + ", put length: 1");
        }
    }

    /**
     * Writes the given character (16 bit, 2 bytes) to the given position in big-endian byte order.
     * This method's speed depends on the system's native byte order, and it is possibly slower than
     * {@link #putCharNativeEndian(int, char)}. For most cases (such as transient storage in memory
     * or serialization for I/O and network), it suffices to know that the byte order in which the
     * value is written is the same as the one in which it is read, and {@link
     * #putCharNativeEndian(int, char)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The char value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public void putCharBigEndian(int index, char value) {
        if (LITTLE_ENDIAN) {
            putCharNativeEndian(index, Character.reverseBytes(value));
        } else {
            putCharNativeEndian(index, value);
        }
    }

    /**
     * Reads a short integer value (16 bit, 2 bytes) from the given position, in little-endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #getShortNativeEndian(int)}. For most cases (such as transient storage in
     * memory or serialization for I/O and network), it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read, and {@link
     * #getShortNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The short value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public short getShort(int index) {
        if (LITTLE_ENDIAN) {
            return getShortNativeEndian(index);
        } else {
            return Short.reverseBytes(getShortNativeEndian(index));
        }
    }

    /**
     * Reads a short integer value (16 bit, 2 bytes) from the given position, composing them into a
     * short value according to the current byte order.
     *
     * @param index The position from which the memory will be read.
     * @return The short value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public short getShortNativeEndian(int index) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 2) {
            return UNSAFE.getShort(heapMemory, pos);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reads a short integer value (16 bit, 2 bytes) from the given position, in big-endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #getShortNativeEndian(int)}. For most cases (such as transient storage in
     * memory or serialization for I/O and network), it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read, and {@link
     * #getShortNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The short value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public short getShortBigEndian(int index) {
        if (LITTLE_ENDIAN) {
            return Short.reverseBytes(getShortNativeEndian(index));
        } else {
            return getShortNativeEndian(index);
        }
    }

    /**
     * Writes the given short integer value (16 bit, 2 bytes) to the given position in little-endian
     * byte order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #putShortNativeEndian(int, short)}. For most cases (such as transient
     * storage in memory or serialization for I/O and network), it suffices to know that the byte
     * order in which the value is written is the same as the one in which it is read, and {@link
     * #putShortNativeEndian(int, short)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The short value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public void putShort(int index, short value) {
        if (LITTLE_ENDIAN) {
            putShortNativeEndian(index, value);
        } else {
            putShortNativeEndian(index, Short.reverseBytes(value));
        }
    }

    /**
     * Writes the given short value into this buffer at the given position, using the native byte
     * order of the system.
     *
     * @param index The position at which the value will be written.
     * @param value The short value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public void putShortNativeEndian(int index, short value) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 2) {
            UNSAFE.putShort(heapMemory, pos, value);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException(
                    "capacity: " + size + ", index: " + index + ", put length: 2");
        }
    }

    /**
     * Writes the given short integer value (16 bit, 2 bytes) to the given position in big-endian
     * byte order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #putShortNativeEndian(int, short)}. For most cases (such as transient
     * storage in memory or serialization for I/O and network), it suffices to know that the byte
     * order in which the value is written is the same as the one in which it is read, and {@link
     * #putShortNativeEndian(int, short)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The short value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 2.
     */
    public void putShortBigEndian(int index, short value) {
        if (LITTLE_ENDIAN) {
            putShortNativeEndian(index, Short.reverseBytes(value));
        } else {
            putShortNativeEndian(index, value);
        }
    }

    /**
     * Reads an int value (32bit, 4 bytes) from the given position, in little-endian byte order.
     * This method's speed depends on the system's native byte order, and it is possibly slower than
     * {@link #getIntNativeEndian(int)}. For most cases (such as transient storage in memory or
     * serialization for I/O and network), it suffices to know that the byte order in which the
     * value is written is the same as the one in which it is read, and {@link
     * #getIntNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The int value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public int getInt(int index) {
        if (LITTLE_ENDIAN) {
            return getIntNativeEndian(index);
        } else {
            return Integer.reverseBytes(getIntNativeEndian(index));
        }
    }

    /**
     * Reads an int value (32bit, 4 bytes) from the given position, in the system's native byte
     * order. This method offers the best speed for integer reading and should be used unless a
     * specific byte order is required. In most cases, it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read (such as transient
     * storage in memory, or serialization for I/O and network), making this method the preferable
     * choice.
     *
     * @param index The position from which the value will be read.
     * @return The int value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public int getIntNativeEndian(int index) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 4) {
            return UNSAFE.getInt(heapMemory, pos);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reads an int value (32bit, 4 bytes) from the given position, in big-endian byte order. This
     * method's speed depends on the system's native byte order, and it is possibly slower than
     * {@link #getIntNativeEndian(int)}. For most cases (such as transient storage in memory or
     * serialization for I/O and network), it suffices to know that the byte order in which the
     * value is written is the same as the one in which it is read, and {@link
     * #getIntNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The int value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public int getIntBigEndian(int index) {
        if (LITTLE_ENDIAN) {
            return Integer.reverseBytes(getIntNativeEndian(index));
        } else {
            return getIntNativeEndian(index);
        }
    }

    /**
     * Reads an unsigned int value from the given position, in little-endian byte order.
     *
     * @param index The position from which the value will be read.
     * @return The unsigned int value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public long getUnsignedInt(int index) {
        return getInt(index) & 0xffffffffL;
    }

    /**
     * Writes the given int value (32bit, 4 bytes) to the given position in little endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #putIntNativeEndian(int, int)}. For most cases (such as transient storage
     * in memory or serialization for I/O and network), it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read, and {@link
     * #putIntNativeEndian(int, int)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The int value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public void putInt(int index, int value) {
        if (LITTLE_ENDIAN) {
            putIntNativeEndian(index, value);
        } else {
            putIntNativeEndian(index, Integer.reverseBytes(value));
        }
    }

    /**
     * Writes the given int value (32bit, 4 bytes) to the given position in the system's native byte
     * order. This method offers the best speed for integer writing and should be used unless a
     * specific byte order is required. In most cases, it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read (such as transient
     * storage in memory, or serialization for I/O and network), making this method the preferable
     * choice.
     *
     * @param index The position at which the value will be written.
     * @param value The int value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public void putIntNativeEndian(int index, int value) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 4) {
            UNSAFE.putInt(heapMemory, pos, value);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException(
                    "capacity: " + size + ", index: " + index + ", put length: 4");
        }
    }

    /**
     * Writes the given int value (32bit, 4 bytes) to the given position in big endian byte order.
     * This method's speed depends on the system's native byte order, and it is possibly slower than
     * {@link #putIntNativeEndian(int, int)}. For most cases (such as transient storage in memory or
     * serialization for I/O and network), it suffices to know that the byte order in which the
     * value is written is the same as the one in which it is read, and {@link
     * #putIntNativeEndian(int, int)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The int value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public void putIntBigEndian(int index, int value) {
        if (LITTLE_ENDIAN) {
            putIntNativeEndian(index, Integer.reverseBytes(value));
        } else {
            putIntNativeEndian(index, value);
        }
    }

    /**
     * Reads a long integer value (64bit, 8 bytes) from the given position, in little endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #getLongNativeEndian(int)}. For most cases (such as transient storage in
     * memory or serialization for I/O and network), it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read, and {@link
     * #getLongNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public long getLong(int index) {
        if (LITTLE_ENDIAN) {
            return getLongNativeEndian(index);
        } else {
            return Long.reverseBytes(getLongNativeEndian(index));
        }
    }

    /**
     * Reads a long value (64bit, 8 bytes) from the given position, in the system's native byte
     * order. This method offers the best speed for long integer reading and should be used unless a
     * specific byte order is required. In most cases, it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read (such as transient
     * storage in memory, or serialization for I/O and network), making this method the preferable
     * choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public long getLongNativeEndian(int index) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 8) {
            return UNSAFE.getLong(heapMemory, pos);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * Reads a long integer value (64bit, 8 bytes) from the given position, in big endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #getLongNativeEndian(int)}. For most cases (such as transient storage in
     * memory or serialization for I/O and network), it suffices to know that the byte order in
     * which the value is written is the same as the one in which it is read, and {@link
     * #getLongNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public long getLongBigEndian(int index) {
        if (LITTLE_ENDIAN) {
            return Long.reverseBytes(getLongNativeEndian(index));
        } else {
            return getLongNativeEndian(index);
        }
    }

    /**
     * Writes the given long value (64bit, 8 bytes) to the given position in little endian byte
     * order. This method's speed depends on the system's native byte order, and it is possibly
     * slower than {@link #putLongNativeEndian(int, long)}. For most cases (such as transient
     * storage in memory or serialization for I/O and network), it suffices to know that the byte
     * order in which the value is written is the same as the one in which it is read, and {@link
     * #putLongNativeEndian(int, long)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public void putLong(int index, long value) {
        if (LITTLE_ENDIAN) {
            putLongNativeEndian(index, value);
        } else {
            putLongNativeEndian(index, Long.reverseBytes(value));
        }
    }

    /**
     * Writes the given long value (64bit, 8 bytes) to the given position in the system's native
     * byte order. This method offers the best speed for long integer writing and should be used
     * unless a specific byte order is required. In most cases, it suffices to know that the byte
     * order in which the value is written is the same as the one in which it is read (such as
     * transient storage in memory, or serialization for I/O and network), making this method the
     * preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public void putLongNativeEndian(int index, long value) {
        final long pos = address + index;
        if (index >= 0 && pos <= addressLimit - 8) {
            UNSAFE.putLong(heapMemory, pos, value);
        } else if (address > addressLimit) {
            throw new IllegalStateException("segment has been freed");
        } else {
            // index is in fact invalid
            throw new IndexOutOfBoundsException(
                    "capacity: " + size + ", index: " + index + ", put length: 8");
        }
    }

    /**
     * Writes the given long value (64bit, 8 bytes) to the given position in big endian byte order.
     * This method's speed depends on the system's native byte order, and it is possibly slower than
     * {@link #putLongNativeEndian(int, long)}. For most cases (such as transient storage in memory
     * or serialization for I/O and network), it suffices to know that the byte order in which the
     * value is written is the same as the one in which it is read, and {@link
     * #putLongNativeEndian(int, long)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public void putLongBigEndian(int index, long value) {
        if (LITTLE_ENDIAN) {
            putLongNativeEndian(index, Long.reverseBytes(value));
        } else {
            putLongNativeEndian(index, value);
        }
    }

    /**
     * Reads a single-precision floating point value (32bit, 4 bytes) from the given position, in
     * little endian byte order. This method's speed depends on the system's native byte order, and
     * it is possibly slower than {@link #getFloatNativeEndian(int)}. For most cases (such as
     * transient storage in memory or serialization for I/O and network), it suffices to know that
     * the byte order in which the value is written is the same as the one in which it is read, and
     * {@link #getFloatNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public float getFloat(int index) {
        return Float.intBitsToFloat(getInt(index));
    }

    /**
     * Reads a single-precision floating point value (32bit, 4 bytes) from the given position, in
     * the system's native byte order. This method offers the best speed for float reading and
     * should be used unless a specific byte order is required. In most cases, it suffices to know
     * that the byte order in which the value is written is the same as the one in which it is read
     * (such as transient storage in memory, or serialization for I/O and network), making this
     * method the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The float value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public float getFloatNativeEndian(int index) {
        return Float.intBitsToFloat(getIntNativeEndian(index));
    }

    /**
     * Reads a single-precision floating point value (32bit, 4 bytes) from the given position, in
     * big endian byte order. This method's speed depends on the system's native byte order, and it
     * is possibly slower than {@link #getFloatNativeEndian(int)}. For most cases (such as transient
     * storage in memory or serialization for I/O and network), it suffices to know that the byte
     * order in which the value is written is the same as the one in which it is read, and {@link
     * #getFloatNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public float getFloatBigEndian(int index) {
        return Float.intBitsToFloat(getIntBigEndian(index));
    }

    /**
     * Writes the given single-precision float value (32bit, 4 bytes) to the given position in
     * little endian byte order. This method's speed depends on the system's native byte order, and
     * it is possibly slower than {@link #putFloatNativeEndian(int, float)}. For most cases (such as
     * transient storage in memory or serialization for I/O and network), it suffices to know that
     * the byte order in which the value is written is the same as the one in which it is read, and
     * {@link #putFloatNativeEndian(int, float)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public void putFloat(int index, float value) {
        putInt(index, Float.floatToRawIntBits(value));
    }

    /**
     * Writes the given single-precision float value (32bit, 4 bytes) to the given position in the
     * system's native byte order. This method offers the best speed for float writing and should be
     * used unless a specific byte order is required. In most cases, it suffices to know that the
     * byte order in which the value is written is the same as the one in which it is read (such as
     * transient storage in memory, or serialization for I/O and network), making this method the
     * preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The float value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public void putFloatNativeEndian(int index, float value) {
        putIntNativeEndian(index, Float.floatToRawIntBits(value));
    }

    /**
     * Writes the given single-precision float value (32bit, 4 bytes) to the given position in big
     * endian byte order. This method's speed depends on the system's native byte order, and it is
     * possibly slower than {@link #putFloatNativeEndian(int, float)}. For most cases (such as
     * transient storage in memory or serialization for I/O and network), it suffices to know that
     * the byte order in which the value is written is the same as the one in which it is read, and
     * {@link #putFloatNativeEndian(int, float)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 4.
     */
    public void putFloatBigEndian(int index, float value) {
        putIntBigEndian(index, Float.floatToRawIntBits(value));
    }

    /**
     * Reads a double-precision floating point value (64bit, 8 bytes) from the given position, in
     * little endian byte order. This method's speed depends on the system's native byte order, and
     * it is possibly slower than {@link #getDoubleNativeEndian(int)}. For most cases (such as
     * transient storage in memory or serialization for I/O and network), it suffices to know that
     * the byte order in which the value is written is the same as the one in which it is read, and
     * {@link #getDoubleNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public double getDouble(int index) {
        return Double.longBitsToDouble(getLong(index));
    }

    /**
     * Reads a double-precision floating point value (64bit, 8 bytes) from the given position, in
     * the system's native byte order. This method offers the best speed for double reading and
     * should be used unless a specific byte order is required. In most cases, it suffices to know
     * that the byte order in which the value is written is the same as the one in which it is read
     * (such as transient storage in memory, or serialization for I/O and network), making this
     * method the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The double value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public double getDoubleNativeEndian(int index) {
        return Double.longBitsToDouble(getLongNativeEndian(index));
    }

    /**
     * Reads a double-precision floating point value (64bit, 8 bytes) from the given position, in
     * big endian byte order. This method's speed depends on the system's native byte order, and it
     * is possibly slower than {@link #getDoubleNativeEndian(int)}. For most cases (such as
     * transient storage in memory or serialization for I/O and network), it suffices to know that
     * the byte order in which the value is written is the same as the one in which it is read, and
     * {@link #getDoubleNativeEndian(int)} is the preferable choice.
     *
     * @param index The position from which the value will be read.
     * @return The long value at the given position.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public double getDoubleBigEndian(int index) {
        return Double.longBitsToDouble(getLongBigEndian(index));
    }

    /**
     * Writes the given double-precision floating-point value (64bit, 8 bytes) to the given position
     * in little endian byte order. This method's speed depends on the system's native byte order,
     * and it is possibly slower than {@link #putDoubleNativeEndian(int, double)}. For most cases
     * (such as transient storage in memory or serialization for I/O and network), it suffices to
     * know that the byte order in which the value is written is the same as the one in which it is
     * read, and {@link #putDoubleNativeEndian(int, double)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public void putDouble(int index, double value) {
        putLong(index, Double.doubleToRawLongBits(value));
    }

    /**
     * Writes the given double-precision floating-point value (64bit, 8 bytes) to the given position
     * in the system's native byte order. This method offers the best speed for double writing and
     * should be used unless a specific byte order is required. In most cases, it suffices to know
     * that the byte order in which the value is written is the same as the one in which it is read
     * (such as transient storage in memory, or serialization for I/O and network), making this
     * method the preferable choice.
     *
     * @param index The position at which the memory will be written.
     * @param value The double value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public void putDoubleNativeEndian(int index, double value) {
        putLongNativeEndian(index, Double.doubleToRawLongBits(value));
    }

    /**
     * Writes the given double-precision floating-point value (64bit, 8 bytes) to the given position
     * in big endian byte order. This method's speed depends on the system's native byte order, and
     * it is possibly slower than {@link #putDoubleNativeEndian(int, double)}. For most cases (such
     * as transient storage in memory or serialization for I/O and network), it suffices to know
     * that the byte order in which the value is written is the same as the one in which it is read,
     * and {@link #putDoubleNativeEndian(int, double)} is the preferable choice.
     *
     * @param index The position at which the value will be written.
     * @param value The long value to be written.
     * @throws IndexOutOfBoundsException Thrown, if the index is negative, or larger than the
     *     segment size minus 8.
     */
    public void putDoubleBigEndian(int index, double value) {
        putLongBigEndian(index, Double.doubleToRawLongBits(value));
    }

    // -------------------------------------------------------------------------
    //                     Bulk Read and Write Methods
    // -------------------------------------------------------------------------

    public void get(DataOutput out, int offset, int length) throws IOException {
        if (address <= addressLimit) {
            if (heapMemory != null) {
                out.write(heapMemory, offset, length);
            } else {
                while (length >= 8) {
                    out.writeLong(getLongBigEndian(offset));
                    offset += 8;
                    length -= 8;
                }

                while (length > 0) {
                    out.writeByte(get(offset));
                    offset++;
                    length--;
                }
            }
        } else {
            throw new IllegalStateException("segment has been freed");
        }
    }

    /**
     * Bulk put method. Copies length memory from the given DataInput to the memory starting at
     * position offset.
     *
     * @param in The DataInput to get the data from.
     * @param offset The position in the memory segment to copy the chunk to.
     * @param length The number of bytes to get.
     * @throws IOException Thrown, if the DataInput encountered a problem upon reading, such as an
     *     End-Of-File.
     */
    public void put(DataInput in, int offset, int length) throws IOException {
        if (address <= addressLimit) {
            if (heapMemory != null) {
                in.readFully(heapMemory, offset, length);
            } else {
                while (length >= 8) {
                    putLongBigEndian(offset, in.readLong());
                    offset += 8;
                    length -= 8;
                }
                while (length > 0) {
                    put(offset, in.readByte());
                    offset++;
                    length--;
                }
            }
        } else {
            throw new IllegalStateException("segment has been freed");
        }
    }

    /**
     * Bulk get method. Copies {@code numBytes} bytes from this memory segment, starting at position
     * {@code offset} to the target {@code ByteBuffer}. The bytes will be put into the target buffer
     * starting at the buffer's current position. If this method attempts to write more bytes than
     * the target byte buffer has remaining (with respect to {@link ByteBuffer#remaining()}), this
     * method will cause a {@link BufferOverflowException}.
     *
     * @param offset The position where the bytes are started to be read from in this memory
     *     segment.
     * @param target The ByteBuffer to copy the bytes to.
     * @param numBytes The number of bytes to copy.
     * @throws IndexOutOfBoundsException If the offset is invalid, or this segment does not contain
     *     the given number of bytes (starting from offset), or the target byte buffer does not have
     *     enough space for the bytes.
     * @throws ReadOnlyBufferException If the target buffer is read-only.
     */
    public void get(int offset, ByteBuffer target, int numBytes) {
        // check the byte array offset and length
        if ((offset | numBytes | (offset + numBytes)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }

        final int targetOffset = target.position();
        final int remaining = target.remaining();

        if (remaining < numBytes) {
            throw new BufferOverflowException();
        }

        if (target.isDirect()) {
            // copy to the target memory directly
            final long targetPointer = getByteBufferAddress(target) + targetOffset;
            final long sourcePointer = address + offset;

            if (sourcePointer <= addressLimit - numBytes) {
                UNSAFE.copyMemory(heapMemory, sourcePointer, null, targetPointer, numBytes);
                target.position(targetOffset + numBytes);
            } else if (address > addressLimit) {
                throw new IllegalStateException("segment has been freed");
            } else {
                throw new IndexOutOfBoundsException();
            }
        } else if (target.hasArray()) {
            // move directly into the byte array
            get(offset, target.array(), targetOffset + target.arrayOffset(), numBytes);

            // this must be after the get() call to ensue that the byte buffer is not
            // modified in case the call fails
            target.position(targetOffset + numBytes);
        } else {
            // other types of byte buffers
            throw new IllegalArgumentException(
                    "The target buffer is not direct, and has no array.");
        }
    }

    /**
     * Bulk put method. Copies {@code numBytes} bytes from the given {@code ByteBuffer}, into this
     * memory segment. The bytes will be read from the target buffer starting at the buffer's
     * current position, and will be written to this memory segment starting at {@code offset}. If
     * this method attempts to read more bytes than the target byte buffer has remaining (with
     * respect to {@link ByteBuffer#remaining()}), this method will cause a {@link
     * BufferUnderflowException}.
     *
     * @param offset The position where the bytes are started to be written to in this memory
     *     segment.
     * @param source The ByteBuffer to copy the bytes from.
     * @param numBytes The number of bytes to copy.
     * @throws IndexOutOfBoundsException If the offset is invalid, or the source buffer does not
     *     contain the given number of bytes, or this segment does not have enough space for the
     *     bytes (counting from offset).
     */
    public void put(int offset, ByteBuffer source, int numBytes) {
        // check the byte array offset and length
        if ((offset | numBytes | (offset + numBytes)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        final int sourceOffset = source.position();
        final int remaining = source.remaining();

        if (remaining < numBytes) {
            throw new BufferUnderflowException();
        }

        if (source.isDirect()) {
            // copy to the target memory directly
            final long sourcePointer = getByteBufferAddress(source) + sourceOffset;
            final long targetPointer = address + offset;

            if (targetPointer <= addressLimit - numBytes) {
                UNSAFE.copyMemory(null, sourcePointer, heapMemory, targetPointer, numBytes);
                source.position(sourceOffset + numBytes);
            } else if (address > addressLimit) {
                throw new IllegalStateException("segment has been freed");
            } else {
                throw new IndexOutOfBoundsException();
            }
        } else if (source.hasArray()) {
            // move directly into the byte array
            put(offset, source.array(), sourceOffset + source.arrayOffset(), numBytes);

            // this must be after the get() call to ensue that the byte buffer is not
            // modified in case the call fails
            source.position(sourceOffset + numBytes);
        } else {
            // other types of byte buffers
            for (int i = 0; i < numBytes; i++) {
                put(offset++, source.get());
            }
        }
    }

    /**
     * Bulk copy method. Copies {@code numBytes} bytes from this memory segment, starting at
     * position {@code offset} to the target memory segment. The bytes will be put into the target
     * segment starting at position {@code targetOffset}.
     *
     * @param offset The position where the bytes are started to be read from in this memory
     *     segment.
     * @param target The memory segment to copy the bytes to.
     * @param targetOffset The position in the target memory segment to copy the chunk to.
     * @param numBytes The number of bytes to copy.
     * @throws IndexOutOfBoundsException If either of the offsets is invalid, or the source segment
     *     does not contain the given number of bytes (starting from offset), or the target segment
     *     does not have enough space for the bytes (counting from targetOffset).
     */
    public void copyTo(int offset, MemorySegment target, int targetOffset, int numBytes) {
        final long thisPointer = this.address + offset;
        final long otherPointer = target.address + targetOffset;

        if ((numBytes | offset | targetOffset) >= 0
                && thisPointer <= this.addressLimit - numBytes
                && otherPointer <= target.addressLimit - numBytes) {
            UNSAFE.copyMemory(
                    this.heapMemory, thisPointer, target.heapMemory, otherPointer, numBytes);
        } else if (this.address > this.addressLimit) {
            throw new IllegalStateException("this memory segment has been freed.");
        } else if (target.address > target.addressLimit) {
            throw new IllegalStateException("target memory segment has been freed.");
        } else {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "offset=%d, targetOffset=%d, numBytes=%d, address=%d, targetAddress=%d",
                            offset, targetOffset, numBytes, this.address, target.address));
        }
    }

    /**
     * Bulk copy method. Copies {@code numBytes} bytes to target unsafe object and pointer. NOTE:
     * This is an unsafe method, no check here, please be careful.
     *
     * @param offset The position where the bytes are started to be read from in this memory
     *     segment.
     * @param target The unsafe memory to copy the bytes to.
     * @param targetPointer The position in the target unsafe memory to copy the chunk to.
     * @param numBytes The number of bytes to copy.
     * @throws IndexOutOfBoundsException If the source segment does not contain the given number of
     *     bytes (starting from offset).
     */
    public void copyToUnsafe(int offset, Object target, int targetPointer, int numBytes) {
        final long thisPointer = this.address + offset;
        if (thisPointer + numBytes > addressLimit) {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "offset=%d, numBytes=%d, address=%d", offset, numBytes, this.address));
        }
        UNSAFE.copyMemory(this.heapMemory, thisPointer, target, targetPointer, numBytes);
    }

    /**
     * Bulk copy method. Copies {@code numBytes} bytes from source unsafe object and pointer. NOTE:
     * This is an unsafe method, no check here, please be careful.
     *
     * @param offset The position where the bytes are started to be write in this memory segment.
     * @param source The unsafe memory to copy the bytes from.
     * @param sourcePointer The position in the source unsafe memory to copy the chunk from.
     * @param numBytes The number of bytes to copy.
     * @throws IndexOutOfBoundsException If this segment can not contain the given number of bytes
     *     (starting from offset).
     */
    public void copyFromUnsafe(int offset, Object source, int sourcePointer, int numBytes) {
        final long thisPointer = this.address + offset;
        if (thisPointer + numBytes > addressLimit) {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "offset=%d, numBytes=%d, address=%d", offset, numBytes, this.address));
        }
        UNSAFE.copyMemory(source, sourcePointer, this.heapMemory, thisPointer, numBytes);
    }

    // -------------------------------------------------------------------------
    //                      Comparisons & Swapping
    // -------------------------------------------------------------------------

    /**
     * Compares two memory segment regions.
     *
     * @param seg2 Segment to compare this segment with
     * @param offset1 Offset of this segment to start comparing
     * @param offset2 Offset of seg2 to start comparing
     * @param len Length of the compared memory region
     * @return 0 if equal, -1 if seg1 &lt; seg2, 1 otherwise
     */
    public int compare(MemorySegment seg2, int offset1, int offset2, int len) {
        while (len >= 8) {
            long l1 = this.getLongBigEndian(offset1);
            long l2 = seg2.getLongBigEndian(offset2);

            if (l1 != l2) {
                return (l1 < l2) ^ (l1 < 0) ^ (l2 < 0) ? -1 : 1;
            }

            offset1 += 8;
            offset2 += 8;
            len -= 8;
        }
        while (len > 0) {
            int b1 = this.get(offset1) & 0xff;
            int b2 = seg2.get(offset2) & 0xff;
            int cmp = b1 - b2;
            if (cmp != 0) {
                return cmp;
            }
            offset1++;
            offset2++;
            len--;
        }
        return 0;
    }

    /**
     * Compares two memory segment regions with different length.
     *
     * @param seg2 Segment to compare this segment with
     * @param offset1 Offset of this segment to start comparing
     * @param offset2 Offset of seg2 to start comparing
     * @param len1 Length of this memory region to compare
     * @param len2 Length of seg2 to compare
     * @return 0 if equal, -1 if seg1 &lt; seg2, 1 otherwise
     */
    public int compare(MemorySegment seg2, int offset1, int offset2, int len1, int len2) {
        final int minLength = Math.min(len1, len2);
        int c = compare(seg2, offset1, offset2, minLength);
        return c == 0 ? (len1 - len2) : c;
    }

    /**
     * Swaps bytes between two memory segments, using the given auxiliary buffer.
     *
     * @param tempBuffer The auxiliary buffer in which to put data during triangle swap.
     * @param seg2 Segment to swap bytes with
     * @param offset1 Offset of this segment to start swapping
     * @param offset2 Offset of seg2 to start swapping
     * @param len Length of the swapped memory region
     */
    public void swapBytes(
            byte[] tempBuffer, MemorySegment seg2, int offset1, int offset2, int len) {
        if ((offset1 | offset2 | len | (tempBuffer.length - len)) >= 0) {
            final long thisPos = this.address + offset1;
            final long otherPos = seg2.address + offset2;

            if (thisPos <= this.addressLimit - len && otherPos <= seg2.addressLimit - len) {
                // this -> temp buffer
                UNSAFE.copyMemory(
                        this.heapMemory, thisPos, tempBuffer, BYTE_ARRAY_BASE_OFFSET, len);

                // other -> this
                UNSAFE.copyMemory(seg2.heapMemory, otherPos, this.heapMemory, thisPos, len);

                // temp buffer -> other
                UNSAFE.copyMemory(
                        tempBuffer, BYTE_ARRAY_BASE_OFFSET, seg2.heapMemory, otherPos, len);
                return;
            } else if (this.address > this.addressLimit) {
                throw new IllegalStateException("this memory segment has been freed.");
            } else if (seg2.address > seg2.addressLimit) {
                throw new IllegalStateException("other memory segment has been freed.");
            }
        }

        // index is in fact invalid
        throw new IndexOutOfBoundsException(
                String.format(
                        "offset1=%d, offset2=%d, len=%d, bufferSize=%d, address1=%d, address2=%d",
                        offset1, offset2, len, tempBuffer.length, this.address, seg2.address));
    }

    /**
     * Equals two memory segment regions.
     *
     * @param seg2 Segment to equal this segment with
     * @param offset1 Offset of this segment to start equaling
     * @param offset2 Offset of seg2 to start equaling
     * @param length Length of the equaled memory region
     * @return true if equal, false otherwise
     */
    public boolean equalTo(MemorySegment seg2, int offset1, int offset2, int length) {
        int i = 0;

        // we assume unaligned accesses are supported.
        // Compare 8 bytes at a time.
        while (i <= length - 8) {
            if (getLongNativeEndian(offset1 + i) != seg2.getLongNativeEndian(offset2 + i)) {
                return false;
            }
            i += 8;
        }

        // cover the last (length % 8) elements.
        while (i < length) {
            if (get(offset1 + i) != seg2.get(offset2 + i)) {
                return false;
            }
            i += 1;
        }

        return true;
    }

    /**
     * Get the heap byte array object.
     *
     * @return Return non-null if the memory is on the heap, and return null if the memory if off
     *     the heap.
     */
    public byte[] getHeapMemory() {
        return heapMemory;
    }
}
