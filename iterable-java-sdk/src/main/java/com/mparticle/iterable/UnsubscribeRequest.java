package com.mparticle.iterable;

import java.util.List;

public class UnsubscribeRequest {
    public int listId;
    public List<Unsubscriber> subscribers;
    public int campaignId;
    public boolean channelUnsubscribe;
}
