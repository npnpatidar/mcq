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

import static com.mcqapp.data.anki.zstd.BitInputStream.peekBits;
import static com.mcqapp.data.anki.zstd.Constants.SIZE_OF_INT;
import static com.mcqapp.data.anki.zstd.Util.ARRAY_BYTE_BASE_OFFSET;
import static com.mcqapp.data.anki.zstd.Util.verify;

class FiniteStateEntropy {
    public static final int MAX_SYMBOL = 255;
    public static final int MIN_TABLE_LOG = 5;

    private FiniteStateEntropy() {
    }

    public static int decompress(FiniteStateEntropy.Table table, final Object inputBase, final long inputAddress, final long inputLimit, byte[] outputBuffer) {
        final long outputAddress = ARRAY_BYTE_BASE_OFFSET;
        final long outputLimit = outputAddress + outputBuffer.length;

        long output = outputAddress;

        // initialize bit stream
        BitInputStream.Initializer initializer = new BitInputStream.Initializer(inputBase, inputAddress, inputLimit);
        initializer.initialize();
        int bitsConsumed = initializer.getBitsConsumed();
        long currentAddress = initializer.getCurrentAddress();
        long bits = initializer.getBits();

        // initialize first FSE stream
        int state1 = (int) peekBits(bitsConsumed, bits, table.log2Size);
        bitsConsumed += table.log2Size;

        BitInputStream.Loader loader = new BitInputStream.Loader(inputBase, inputAddress, currentAddress, bits, bitsConsumed);
        loader.load();
        bits = loader.getBits();
        bitsConsumed = loader.getBitsConsumed();
        currentAddress = loader.getCurrentAddress();

        // initialize second FSE stream
        int state2 = (int) peekBits(bitsConsumed, bits, table.log2Size);
        bitsConsumed += table.log2Size;

        loader = new BitInputStream.Loader(inputBase, inputAddress, currentAddress, bits, bitsConsumed);
        loader.load();
        bits = loader.getBits();
        bitsConsumed = loader.getBitsConsumed();
        currentAddress = loader.getCurrentAddress();

        byte[] symbols = table.symbol;
        byte[] numbersOfBits = table.numberOfBits;
        int[] newStates = table.newState;

        // decode 4 symbols per loop
        while (output <= outputLimit - 4) {
            int numberOfBits;

            SafeAccess.putByte(outputBuffer, output, symbols[state1]);
            numberOfBits = numbersOfBits[state1];
            state1 = (int) (newStates[state1] + peekBits(bitsConsumed, bits, numberOfBits));
            bitsConsumed += numberOfBits;

            SafeAccess.putByte(outputBuffer, output + 1, symbols[state2]);
            numberOfBits = numbersOfBits[state2];
            state2 = (int) (newStates[state2] + peekBits(bitsConsumed, bits, numberOfBits));
            bitsConsumed += numberOfBits;

            SafeAccess.putByte(outputBuffer, output + 2, symbols[state1]);
            numberOfBits = numbersOfBits[state1];
            state1 = (int) (newStates[state1] + peekBits(bitsConsumed, bits, numberOfBits));
            bitsConsumed += numberOfBits;

            SafeAccess.putByte(outputBuffer, output + 3, symbols[state2]);
            numberOfBits = numbersOfBits[state2];
            state2 = (int) (newStates[state2] + peekBits(bitsConsumed, bits, numberOfBits));
            bitsConsumed += numberOfBits;

            output += SIZE_OF_INT;

            loader = new BitInputStream.Loader(inputBase, inputAddress, currentAddress, bits, bitsConsumed);
            boolean done = loader.load();
            bitsConsumed = loader.getBitsConsumed();
            bits = loader.getBits();
            currentAddress = loader.getCurrentAddress();
            if (done) {
                break;
            }
        }

        while (true) {
            verify(output <= outputLimit - 2, inputAddress, "Output buffer is too small");
            SafeAccess.putByte(outputBuffer, output++, symbols[state1]);
            int numberOfBits = numbersOfBits[state1];
            state1 = (int) (newStates[state1] + peekBits(bitsConsumed, bits, numberOfBits));
            bitsConsumed += numberOfBits;

            loader = new BitInputStream.Loader(inputBase, inputAddress, currentAddress, bits, bitsConsumed);
            loader.load();
            bitsConsumed = loader.getBitsConsumed();
            bits = loader.getBits();
            currentAddress = loader.getCurrentAddress();

            if (loader.isOverflow()) {
                SafeAccess.putByte(outputBuffer, output++, symbols[state2]);
                break;
            }

            verify(output <= outputLimit - 2, inputAddress, "Output buffer is too small");
            SafeAccess.putByte(outputBuffer, output++, symbols[state2]);
            int numberOfBits1 = numbersOfBits[state2];
            state2 = (int) (newStates[state2] + peekBits(bitsConsumed, bits, numberOfBits1));
            bitsConsumed += numberOfBits1;

            loader = new BitInputStream.Loader(inputBase, inputAddress, currentAddress, bits, bitsConsumed);
            loader.load();
            bitsConsumed = loader.getBitsConsumed();
            bits = loader.getBits();
            currentAddress = loader.getCurrentAddress();

            if (loader.isOverflow()) {
                SafeAccess.putByte(outputBuffer, output++, symbols[state1]);
                break;
            }
        }

        return (int) (output - outputAddress);
    }

    public static final class Table {
        final int[] newState;
        final byte[] symbol;
        final byte[] numberOfBits;
        int log2Size;

        public Table(int log2Capacity) {
            int capacity = 1 << log2Capacity;
            newState = new int[capacity];
            symbol = new byte[capacity];
            numberOfBits = new byte[capacity];
        }

        public Table(int log2Size, int[] newState, byte[] symbol, byte[] numberOfBits) {
            int size = 1 << log2Size;
            if (newState.length != size || symbol.length != size || numberOfBits.length != size) {
                throw new IllegalArgumentException("Expected arrays to match provided size");
            }

            this.log2Size = log2Size;
            this.newState = newState;
            this.symbol = symbol;
            this.numberOfBits = numberOfBits;
        }
    }
}
