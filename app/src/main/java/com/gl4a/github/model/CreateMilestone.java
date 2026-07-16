package com.gl4a.github.model;
import java.util.Date;
/** Stub for GitHub SDK CreateMilestone request. */
public class CreateMilestone {
    private String title, description, state;
    private Date dueOn;
    private CreateMilestone() {}
    public String title() { return title; }
    public String description() { return description; }
    public String state() { return state; }
    public Date dueOn() { return dueOn; }
    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final CreateMilestone r = new CreateMilestone();
        public Builder title(String t) { r.title = t; return this; }
        public Builder description(String d) { r.description = d; return this; }
        public Builder state(String s) { r.state = s; return this; }
        public Builder dueOn(Date d) { r.dueOn = d; return this; }
        public CreateMilestone build() { return r; }
    }
}
