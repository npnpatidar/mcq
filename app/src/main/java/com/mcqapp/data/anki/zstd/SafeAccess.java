/*
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
// Ported from com.qyntrax:unzstd 0.1.0 (Apache-2.0), itself a port of airlift/aircompressor; only the memory access was rewritten to avoid VarHandle.
package com.mcqapp.data.anki.zstd;

/**
 * Android-safe, pure-Java memory access for this decoder.
 *
 * <p>Memory is always a {@code byte[]}. Addresses are plain array indices
 * because {@link Util#ARRAY_BYTE_BASE_OFFSET} is {@code 0}, so the
 * {@code (int) index} truncation below is exact for every valid input.
 *
 * <p>Multi-byte values are assembled byte by byte in little-endian order —
 * the ZSTD wire order — instead of through {@code VarHandle} (API 33+) or
 * {@code sun.misc.Unsafe} (whose array-offset fields Android does not
 * provide). Imports are rare and decompression speed is not a concern here.
 */
final class SafeAccess {

    private SafeAccess() {
    }

    static byte getByte(Object base, long index) {
        return ((byte[]) base)[(int) index];
    }

    static short getShort(Object base, long index) {
        byte[] array = (byte[]) base;
        int i = (int) index;
        return (short) ((array[i] & 0xFF)
                | ((array[i + 1] & 0xFF) << 8));
    }

    static int getInt(Object base, long index) {
        byte[] array = (byte[]) base;
        int i = (int) index;
        return (array[i] & 0xFF)
                | ((array[i + 1] & 0xFF) << 8)
                | ((array[i + 2] & 0xFF) << 16)
                | ((array[i + 3] & 0xFF) << 24);
    }

    static long getLong(Object base, long index) {
        byte[] array = (byte[]) base;
        int i = (int) index;
        return ((long) array[i] & 0xFF)
                | (((long) array[i + 1] & 0xFF) << 8)
                | (((long) array[i + 2] & 0xFF) << 16)
                | (((long) array[i + 3] & 0xFF) << 24)
                | (((long) array[i + 4] & 0xFF) << 32)
                | (((long) array[i + 5] & 0xFF) << 40)
                | (((long) array[i + 6] & 0xFF) << 48)
                | (((long) array[i + 7] & 0xFF) << 56);
    }

    static void putByte(Object base, long index, byte value) {
        ((byte[]) base)[(int) index] = value;
    }

    static void putShort(Object base, long index, short value) {
        byte[] array = (byte[]) base;
        int i = (int) index;
        array[i] = (byte) value;
        array[i + 1] = (byte) (value >>> 8);
    }

    static void putInt(Object base, long index, int value) {
        byte[] array = (byte[]) base;
        int i = (int) index;
        array[i] = (byte) value;
        array[i + 1] = (byte) (value >>> 8);
        array[i + 2] = (byte) (value >>> 16);
        array[i + 3] = (byte) (value >>> 24);
    }

    static void putLong(Object base, long index, long value) {
        byte[] array = (byte[]) base;
        int i = (int) index;
        array[i] = (byte) value;
        array[i + 1] = (byte) (value >>> 8);
        array[i + 2] = (byte) (value >>> 16);
        array[i + 3] = (byte) (value >>> 24);
        array[i + 4] = (byte) (value >>> 32);
        array[i + 5] = (byte) (value >>> 40);
        array[i + 6] = (byte) (value >>> 48);
        array[i + 7] = (byte) (value >>> 56);
    }

    static void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long size) {
        // srcBase/destBase are always byte[] at runtime (the whole API threads the
        // buffer as Object). arraycopy takes Object, so no cast is needed; the int
        // casts on the offsets/size are required.
        //noinspection SuspiciousSystemArraycopy
        System.arraycopy(srcBase, (int) srcOffset, destBase, (int) destOffset, (int) size);
    }
}
