// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static onl.ycode.stormify.TypeUtils.castTo;

class DefaultDataConverter {
    private DefaultDataConverter() {
    }

    static void validate(String fieldName) {
        // validate field
        if (fieldName == null)
            throw new IllegalArgumentException("Field name cannot be null");
        fieldName = fieldName.trim();
        if (fieldName.isEmpty())
            throw new IllegalArgumentException("Field name cannot be empty");
    }

    static QueryValue textData(BooleanSupplier isCaseSensitive) {
        return (column, input, args) -> {
            if (!isCaseSensitive.getAsBoolean()) {
                column = "LOWER(" + column + ")";
                input = input.toLowerCase();
            }
            if (!input.startsWith("*") && !input.endsWith("*")) input = "*" + input + "*";
            input = input.replace('*', '%');
            args.accept(input);
            return column + (input.contains("%") ? " LIKE ?" : " = ?");
        };
    }

    static QueryValue numericData(Class<?> classType) {
        return (column, input, args) -> {
            try {
                return breakdownParts(column, input, part ->
                        args.accept(castTo(classType, new BigDecimal(part))));
            } catch (Exception e) {
                throw new DataConverterException("Unable to convert '" + input + "' to number", e);
            }
        };
    }

    static QueryValue getTimeRelatedData(Class<?> classType) {
        return (column, input, args) -> {
            try {
                return breakdownParts(column, input, part -> {
                    Temporal data;
                    if (part.contains(":") && part.contains("-"))
                        data = LocalDateTime.parse(part);
                    else if (part.contains(":"))
                        data = LocalTime.parse(part);
                    else
                        data = LocalDate.parse(part);
                    args.accept(castTo(classType, data));
                });
            } catch (Exception e) {
                throw new DataConverterException("Unable to convert '" + input + "' to " + "temporal", e);
            }
        };
    }

    private static String breakdownParts(String column, String userInput, Consumer<String> args) throws DataConverterException {
        String input = userInput.trim();
        boolean biggerOrEqual = input.startsWith(">=");
        boolean smallerOrEqual = input.startsWith("<=");
        boolean bigger = !biggerOrEqual && input.startsWith(">");
        boolean smaller = !smallerOrEqual && input.startsWith("<");
        int dots = input.indexOf("...");
        if ((bigger || smaller || biggerOrEqual || smallerOrEqual) && dots >= 0)
            throw new DataConverterException("Cannot use '...' together with '<' or '>'");
        if (bigger || smaller) {
            args.accept(part(input.substring(1), userInput));
            return column + (bigger ? " > ?" : " < ?");
        } else if (biggerOrEqual || smallerOrEqual) {
            args.accept(part(input.substring(2), userInput));
            return column + (biggerOrEqual ? " >= ?" : " <= ?");
        } else if (dots >= 0) {
            String[] parts = input.split("\\.\\.\\.");
            if (parts.length != 2)
                throw new DataConverterException("Invalid range format");
            args.accept(part(parts[0], userInput));
            args.accept(part(parts[1], userInput));
            return column + " BETWEEN ? AND ?";
        } else {
            args.accept(part(input, userInput));
            return column + " = ?";
        }
    }

    private static String part(String input, String fullData) throws DataConverterException {
        input = input.trim();
        if (input.isEmpty())
            throw new DataConverterException("Invalid syntax, a required part was not found: '" + fullData + "'");
        return input;
    }
}
