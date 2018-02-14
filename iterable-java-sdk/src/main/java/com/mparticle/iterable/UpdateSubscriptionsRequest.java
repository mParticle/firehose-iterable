package com.mparticle.iterable;

import java.util.List;

public class UpdateSubscriptionsRequest {
    public String email;
    public List<Integer> emailListIds;
    public List<Integer> unsubscribedChannelIds;
    public List<Integer> unsubscribedMessageTypeIds;
    public Integer campaignId;
    public Integer templateId;
}
