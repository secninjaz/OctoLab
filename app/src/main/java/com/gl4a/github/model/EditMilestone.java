package com.gl4a.github.model;
import java.util.Date;
/** Stub for GitHub SDK EditMilestone request. */
public class EditMilestone {
    private String title, description, state;
    private Date dueOn;
    private EditMilestone() {}
    public String title() { return title; }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final EditMilestone r = new EditMilestone();
        public Builder title(String t) { r.title = t; return this; }
        public Builder description(String d) { r.description = d; return this; }
        public Builder state(String s) { r.state = s; return this; }
        public Builder dueOn(Date d) { r.dueOn = d; return this; }
        public EditMilestone build() { return r; }
    }
}
