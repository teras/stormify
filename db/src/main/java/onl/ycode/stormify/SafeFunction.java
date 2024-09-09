// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

interface SafeFunction<I, O> {
    O apply(I input) throws Exception;
}
