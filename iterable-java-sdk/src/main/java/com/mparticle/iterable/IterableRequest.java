package com.mparticle.iterable;

import java.util.Map;

public abstract class IterableRequest {


    /**
     * Either email or userId must be passed in to identify the user.  If both are passed in, email takes precedence.
     */
    public String email;
    /**
     *  Time event happened. Set to the time event was received if unspecified. Expects a unix timestamp.,
     */
    public int createdAt;
    /**
     *  Additional data associated with event (i.e. item id, item amount),
     */
    public Map<String, String> dataFields;
    /**
     * userId that was passed into the updateUser call
     */
    public String userId;
    /**
     * Campaign tied to conversion
     */
    public int campaignId;

    public int templateId;

    public IterableRequest() {
        super();
    }


}
