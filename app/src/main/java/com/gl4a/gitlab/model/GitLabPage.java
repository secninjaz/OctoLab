package com.gl4a.gitlab.model;

import java.util.List;

/**
 * Wraps a paginated GitLab API response. Mirrors the GitHub SDK's Page<T> concept.
 * Page metadata comes from response headers (X-Page, X-Next-Page, X-Total-Pages).
 */
public class GitLabPage<T> {
    private List<T> items;
    private int currentPage;
    private int nextPage;
    private int totalPages;
    private int totalItems;

    /** No-arg constructor for building a page manually via setItems(). */
    public GitLabPage() {
        this.items = new java.util.ArrayList<>();
        this.currentPage = 1;
        this.nextPage = 0;
        this.totalPages = 1;
        this.totalItems = 0;
    }

    public GitLabPage(List<T> items, int currentPage, int nextPage, int totalPages, int totalItems) {
        this.items = items;
        this.currentPage = currentPage;
        this.nextPage = nextPage;
        this.totalPages = totalPages;
        this.totalItems = totalItems;
    }

    /** Set items list (used when building a page from a raw list response). */
    public void setItems(List<T> items) { this.items = items; }

    public List<T> items() { return items; }
    public int currentPage() { return currentPage; }
    public int nextPage() { return nextPage; }
    public int totalPages() { return totalPages; }
    public int totalItems() { return totalItems; }
    public boolean hasNextPage() { return nextPage > 0 && nextPage > currentPage; }

    // GitHub SDK Page<T> compat
    public List<T> asList() { return items; }
    public boolean last() { return !hasNextPage(); }

    /**
     * Returns the next page number, or null if this is the last page.
     * PagedDataBaseFragment filters with: pair.second == null || pair.second != 0
     * Returning null (not 0) signals "last page" and lets the data through.
     */
    public Integer next() { return nextPage > 0 ? nextPage : null; }
}
