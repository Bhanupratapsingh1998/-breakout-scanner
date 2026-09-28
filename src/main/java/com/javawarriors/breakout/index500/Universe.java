package com.javawarriors.breakout.index500;

/**
 * Which set of stocks an analysis run covers.
 *
 * <p>The analysis itself does not vary by universe. The same eighteen months of daily bars, the
 * same indicator snapshot, the same fifteen detectors and the same opportunity score run over
 * whichever symbols are handed to them - so a second tab is a second <em>list</em>, not a second
 * engine. This enum is that list, plus the words the API and the UI use for it.
 *
 * <p>Adding a third (a sector universe, a custom basket) means adding a constant here and a slug
 * to the controller's path pattern. Nothing in the scan, the scoring or the table has to know.
 */
public enum Universe {

    /** The Nifty 500, plus the curated top-100 - the app's original universe. */
    NIFTY_500("index500", "Index 500 Analysis", "Nifty 500"),

    /**
     * The stocks with listed futures and options.
     *
     * <p>Around 210 names, and not a subset of any index: SEBI sets eligibility from turnover and
     * position limits, so the list overlaps the Nifty 500 heavily but is maintained separately and
     * on its own schedule.
     */
    FNO("fno", "F&O Analysis", "F&O stocks");

    private final String slug;
    private final String label;
    private final String shortLabel;

    Universe(String slug, String label, String shortLabel) {
        this.slug = slug;
        this.label = label;
        this.shortLabel = shortLabel;
    }

    /** The path segment this universe answers on, e.g. {@code /api/fno/analysis}. */
    public String slug() {
        return slug;
    }

    /** The tab's name. */
    public String label() {
        return label;
    }

    /** The universe's name in running text, e.g. "486 of 504 F&O stocks match". */
    public String shortLabel() {
        return shortLabel;
    }

    /**
     * The universe a path slug names.
     *
     * @throws IllegalArgumentException when the slug is not one of ours, which the controller turns
     *                                  into a 400 rather than letting it surface as a 500
     */
    public static Universe ofSlug(String slug) {
        for (Universe u : values()) {
            if (u.slug.equalsIgnoreCase(slug)) return u;
        }
        throw new IllegalArgumentException("unknown universe '" + slug + "'");
    }
}
