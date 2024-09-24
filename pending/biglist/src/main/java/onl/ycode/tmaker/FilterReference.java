// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import onl.ycode.stormify.TypeUtils;

import java.time.temporal.Temporal;
import java.util.*;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static onl.ycode.tmaker.DefaultDataConverter.*;
import static onl.ycode.tmaker.FilteredList.NULL;

/**
 * A reference to a given filter. Using this reference,
 * the user can set a value for the filter or manipulate its properties.
 */
public class FilterReference extends TableReference {
    private final FilteredList<?> root;
    private QueryValue stringToValue;
    private Collection<Object> args = emptyList();
    private String query = "";
    private boolean isCaseSensitive = false;

    FilterReference(FilteredList<?> root, Node node) {
        super(node);
        this.root = root;
    }

    boolean valueExists() {
        return !query.isEmpty();
    }

    /**
     * Sets the value of the filter. If the value is null, the filter is disabled.
     * If the value is {@link FilteredList#NULL}, the filter is set to search for NULL values.
     * <p>
     * If an {@link DataConverterException} is thrown during the conversion process,
     * the exception is caught and the error message is returned.
     * <p>
     * For numeric and temporal data types, the default parser supports ranges and comparisons.
     * To define a range, use the syntax {@code 'min ... max'}, where min and max are inclusive.
     * To define a comparison, use the syntax {@code 'operator value'}, where operator is one of
     * <ul>
     *     <li>{@code <} for less than</li>
     *     <li>{@code <=} for less than or equal</li>
     *     <li>{@code >} for greater than</li>
     *     <li>{@code >=} for greater than or equal</li>
     * </ul>
     * For example:
     * <ul>
     *     <li>for values between 10 and 20: {@code '10 ... 20'}</li>
     *     <li>for values greater than 10: {@code '> 10'}</li>
     *     <li>for values less than or equal to 20: {@code '<= 20'}</li>
     * </ul>
     *
     * @param value The value to set
     * @return An error message, or null if the value was set successfully
     */
    public String setValue(String value) {
        if (value == null)
            return parseSetValue(emptyList(), "");
        else if (NULL.equals(value))
            return parseSetValue(emptyList(), getColumnName() + " IS NULL");
        else {
            List<Object> newValue = new ArrayList<>();
            try {
                return parseSetValue(newValue, getStringToValueConverter().getQuery(getColumnName(), value, newValue::add));
            } catch (DataConverterException e) {
                return e.getMessage();
            }
        }
    }

    private String parseSetValue(Collection<Object> newArgs, String newQuery) {
        if (!Objects.equals(args, newArgs) || !Objects.equals(query, newQuery))
            root.invalidate();
        args = newArgs;
        query = newQuery;
        return null;
    }

    /**
     * The converter to use, to transform the textual value of the filter to a database value. If not set, a default converter is used
     * based on the data type of the column. Note that this method will return the
     * full query part, including the column name, the "?" symbols,
     * and the value should manually be stored in the args consumer.
     * <p>
     * If only a simple converter is needed, use {@link #setSimpleValueConverter(SimpleValue)} instead.
     * <p>
     * The lambda function should return the query part that corresponds to the filter value and throw
     * a {@link DataConverterException} when the input cannot be converted properly.
     *
     * @param converter The converter to use
     * @return This reference
     */
    public FilterReference setQueryValueConverter(QueryValue converter) {
        requireNonNull(converter, "Converter from String to Database data type cannot be null");
        stringToValue = converter;
        return this;
    }

    /**
     * Sets a simple converter to transform the textual value of the filter to a database value.
     * If not set, a default converter is used.
     * <p>
     * Use this method when the conversion is simple and a simple equality with the database field is enough.
     * For example, when there is an enum field, it is possible to use this method to convert the textual value
     * to a number.
     *
     * @param converter The converter to use, to transform the textual value of the filter to a database value
     * @return This reference
     * @see #setQueryValueConverter(QueryValue)
     */
    public FilterReference setSimpleValueConverter(SimpleValue converter) {
        requireNonNull(converter, "Converter from String to Database data type cannot be null");
        stringToValue = (column, input, cargs) -> {
            cargs.accept(TypeUtils.castTo(getType(), converter.convert(input)));
            return column + " = ?";
        };
        return this;
    }

    /**
     * For text filters, sets whether the search should be case sensitive or not. If not set, the default is case insensitive.
     *
     * @param caseSensitive Whether the search should be case sensitive
     * @return This reference
     */
    public FilterReference setCaseSensitive(boolean caseSensitive) {
        isCaseSensitive = caseSensitive;
        return this;
    }

    private boolean isCaseSensitive() {
        return isCaseSensitive;
    }

    private QueryValue getStringToValueConverter() {
        if (stringToValue == null) {
            // Guess default converter
            Class<?> type = TypeUtils.getWrapper(getType());
            if (CharSequence.class.isAssignableFrom(type))
                stringToValue = textData(this::isCaseSensitive);
            else if (Number.class.isAssignableFrom(type))
                stringToValue = numericData(type);
            else if (Date.class.isAssignableFrom(type) || Temporal.class.isAssignableFrom(type))
                stringToValue = getTimeRelatedData(type);
            else
                throw new IllegalArgumentException("Unsupported data type: " + type.getName());

        }
        return stringToValue;
    }

    boolean appendConstraint(StringBuilder out, Consumer<Object> args) {
        if (valueExists()) {
            if (out.length() > 0)
                out.append(" OR ");
            out.append(query);
            this.args.forEach(args);
            return true;
        } else
            return false;
    }
}
