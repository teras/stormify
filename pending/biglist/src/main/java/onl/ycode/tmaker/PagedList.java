// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import java.util.AbstractList;
import java.util.List;

/**
 * A list that loads its elements in pages. The list is divided into pages of
 * fixed size and only the current page is loaded in memory. The list is
 * accessed as if it were a single list.
 *
 * @param <T> The type of elements in the list
 */
public abstract class PagedList<T> extends AbstractList<T> {

    private int pageSize = 15;
    private List<T> fragment;
    private int lowBound;  // inclusize
    private int upperBound; // exclusive

    /**
     * Creates a new paged list with the default page size of 15.
     */
    protected PagedList() {
    }

    /**
     * Retrieve a fragment of the list. The fragment is a sublist of the list starting
     * at the low bound and ending at the upper bound. The bounds are inclusive and
     * exclusive respectively.
     * <p>
     * This fragment is loaded into memory and used to access the virtual elements of the full list.
     * The fragment is loaded only once and is invalidated when the list is modified or the currently
     * accessed element is outside the bounds of the fragment.
     * The fragment is loaded lazily when the list is accessed.
     *
     * @param lowBound   The lower bound of the fragment
     * @param upperBound The upper bound of the fragment
     * @return The fragment of the list
     */
    protected abstract List<T> getFragment(int lowBound, int upperBound);

    /**
     * Sets the page size of the list. The page size is the number of elements loaded
     * into memory at a time. The default page size is 15.
     *
     * @param pageSize The new page size
     */
    public void setPageSize(int pageSize) {
        if (pageSize < 1)
            throw new IllegalArgumentException("Page size must be at least 1");
        if (pageSize == this.pageSize)
            return;
        this.pageSize = pageSize;
        invalidate();
    }

    /**
     * Retrieves the current page size of the list.
     *
     * @return The current page size
     */
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public synchronized T get(int index) {
        ensurePage(index);
        return fragment.get(index - lowBound);
    }

    @Override
    public synchronized T set(int index, T element) {
        ensurePage(index);
        return fragment.set(index - lowBound, element);
    }

    @Override
    public synchronized int indexOf(Object o) {
        throw new UnsupportedOperationException("Paged Lists do not support indexOf");
    }

    @Override
    public synchronized boolean add(T e) {
        invalidate();
        return true;
    }

    @Override
    public synchronized void add(int index, T element) {
        invalidate();
    }

    @Override
    public synchronized boolean remove(Object o) {
        invalidate();
        return true;
    }

    @Override
    public synchronized T remove(int index) {
        invalidate();
        return null;
    }

    private synchronized void ensurePage(int index) {
        int size = size();
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
        if (fragment == null || index < lowBound || index >= upperBound) {
            int page = index / pageSize;
            lowBound = page * pageSize;
            upperBound = Math.min(size, (page + 1) * pageSize);
            fragment = getFragment(lowBound, upperBound);
        }
    }

    /**
     * Invalidates the current fragment of the list. The fragment is reloaded when the list is accessed.
     */
    protected synchronized void invalidate() {
        fragment = null;
    }
}
