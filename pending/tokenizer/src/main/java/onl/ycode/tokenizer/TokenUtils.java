// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.util.Arrays;

/**
 *
 */
class TokenUtils {
    @SuppressWarnings("SameParameterValue")
    static String repetitions(char source, int howMany) {
        char[] buffer = new char[howMany];
        Arrays.fill(buffer, source);
        return new String(buffer);
    }

    private TokenUtils() {
    }
}
