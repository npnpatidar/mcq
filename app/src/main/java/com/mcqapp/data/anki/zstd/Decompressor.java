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

public interface Decompressor {
    /**
     * @return number of bytes written to the output
     */
    int decompress(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength)
            throws MalformedInputException;
}
