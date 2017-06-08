package com.mparticle.iterable;

import java.util.Map;

public class TrackRequest {

    /**
     * Required
     *
     * Name of event,
     */
    private String eventName;

    /**
     * Either email or userId must be passed in to identify the user.  If both are passed in, email takes precedence.
     */
    public String email;
    /**
     *  Time event happened. Set to the time event was received if unspecified. Expects a unix timestamp.
     */
    public Integer createdAt;
    /**
     *  Additional data associated with event (i.e. item id, item amount),
     */
    public Map<String, Object> dataFields;
    /**
     * userId that was passed into the updateUser call
     */
    public String userId;
    /**
     * Campaign tied to conversion
     */
    public Integer campaignId;

    public Integer templateId;

    public TrackRequest() {
        super();
    }

    public TrackRequest(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return this.eventName;
    }
}
